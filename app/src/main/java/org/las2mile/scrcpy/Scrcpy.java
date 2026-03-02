package org.las2mile.scrcpy;

import android.app.Service;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import org.las2mile.scrcpy.decoder.VideoDecoder;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class Scrcpy extends Service {

    private static final long PACKET_FLAG_CONFIG = 1L << 63;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 62;
    private static final int DEVICE_NAME_FIELD_LENGTH = 64;
    private static final String SOCKET_NAME_PREFIX = "scrcpy";

    private static final int CONTROL_MSG_TYPE_INJECT_KEYCODE = 0;
    private static final int CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT = 2;
    private static final int CONTROL_MSG_TYPE_SET_CLIPBOARD = 9;

    private static final int DEVICE_MSG_TYPE_CLIPBOARD = 0;
    private static final int DEVICE_MSG_TYPE_ACK_CLIPBOARD = 1;
    private static final int DEVICE_MSG_TYPE_UHID_OUTPUT = 2;

    private static final int CONTROL_CLIPBOARD_TEXT_MAX_LENGTH = (1 << 18) - 14;
    private static final int DEVICE_CLIPBOARD_TEXT_MAX_LENGTH = (1 << 18) - 5;

    private static final int VIDEO_CODEC_H264 = 0x68_32_36_34;
    private static final int VIDEO_CODEC_H265 = 0x68_32_36_35;
    private static final int VIDEO_CODEC_AV1 = 0x00_61_76_31;
    private static final int VIDEO_CODEC_MJPEG = 0x6d_6a_70_67;
    private static final String VIDEO_MIME_AVC = "video/avc";
    private static final String VIDEO_MIME_HEVC = "video/hevc";
    private static final String VIDEO_MIME_AV1 = "video/av01";

    private static final int AUDIO_CODEC_RAW = 0x00_72_61_77;
    private static final int AUDIO_CODEC_AAC = 0x00_61_61_63;
    private static final int AUDIO_CODEC_OPUS = 0x6f_70_75_73;
    private static final int AUDIO_CODEC_FLAC = 0x66_6c_61_63;
    private static final String AUDIO_MIME_AAC = "audio/mp4a-latm";
    private static final String AUDIO_MIME_OPUS = "audio/opus";
    private static final String AUDIO_MIME_FLAC = "audio/flac";
    private static final int AUDIO_STREAM_DISABLED = 0;
    private static final int AUDIO_STREAM_ERROR = 1;
    private static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int MJPEG_PACKET_HEADER_BYTES = 8;

    private String serverAdr;
    private int scid = -1;
    private volatile Surface surface;
    private int screenWidth;
    private int screenHeight;
    private VideoDecoder videoDecoder;
    private final AtomicBoolean updateAvailable = new AtomicBoolean(false);
    private final IBinder mBinder = new MyServiceBinder();
    private final AtomicBoolean letServiceRunning = new AtomicBoolean(true);
    private ServiceCallbacks serviceCallbacks;
    private final int[] remoteDevResolution = new int[2];
    private boolean socketStatus = false;
    private final AtomicBoolean connectionReported = new AtomicBoolean(false);
    private final LinkedBlockingQueue<byte[]> controlQueue = new LinkedBlockingQueue<>(256);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<String> lastRemoteClipboardText = new AtomicReference<>();
    private final AtomicLong clipboardSequence = new AtomicLong(1);
    private volatile int streamWidth;
    private volatile int streamHeight;
    private Thread connectionThread;
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private final AtomicBoolean clipboardListenerRegistered = new AtomicBoolean(false);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect imageDstRect = new Rect();

    @Override
    public IBinder onBind(android.content.Intent intent) {
        return mBinder;
    }

    public void setServiceCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }

    public void setParms(Surface newSurface, int newWidth, int newHeight) {
        this.screenWidth = newWidth;
        this.screenHeight = newHeight;
        this.surface = newSurface;
        if (videoDecoder != null) {
            videoDecoder.start();
        }
        updateAvailable.set(true);
    }

    public void start(Surface surface, String serverAdr, int screenHeight, int screenWidth, int scid) {
        letServiceRunning.set(true);
        registerClipboardSync();
        this.videoDecoder = null;
        this.serverAdr = serverAdr;
        this.scid = scid;
        this.socketStatus = false;
        notifyConnectionStateChanged(false);
        this.screenHeight = screenHeight;
        this.screenWidth = screenWidth;
        this.surface = surface;
        connectionThread = new Thread(this::startConnection, "scrcpy-connection");
        connectionThread.start();
    }

    public void pause() {
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
    }

    public void resume() {
        if (videoDecoder != null) {
            videoDecoder.start();
        }
        updateAvailable.set(true);
    }

    public void StopService() {
        letServiceRunning.set(false);
        unregisterClipboardSync();
        if (connectionThread != null) {
            connectionThread.interrupt();
        }
        notifyConnectionStateChanged(false);
        stopVideoDecoder();
        stopSelf();
    }

    public boolean touchevent(MotionEvent touchEvent, int displayW, int displayH) {
        int remoteW = streamWidth > 0 ? streamWidth : remoteDevResolution[0];
        int remoteH = streamHeight > 0 ? streamHeight : remoteDevResolution[1];
        if (remoteW <= 0 || remoteH <= 0 || displayW <= 0 || displayH <= 0) {
            return false;
        }

        int x = clamp((int) (touchEvent.getX() * remoteW / displayW), 0, remoteW - 1);
        int y = clamp((int) (touchEvent.getY() * remoteH / displayH), 0, remoteH - 1);
        int action = touchEvent.getActionMasked();
        int actionButton = touchEvent.getActionButton();
        int buttons = touchEvent.getButtonState();
        float pressure = touchEvent.getPressure();

        byte[] msg = buildTouchControlMessage(action, -2L, x, y, remoteW, remoteH, pressure, actionButton, buttons);
        enqueueControlMessage(msg);
        return true;
    }

    public int[] get_remote_device_resolution() {
        return remoteDevResolution;
    }

    public boolean check_socket_connection() {
        return socketStatus;
    }

    public void sendKeyevent(int keycode) {
        enqueueControlMessage(buildKeyControlMessage(KeyEvent.ACTION_DOWN, keycode));
        enqueueControlMessage(buildKeyControlMessage(KeyEvent.ACTION_UP, keycode));
    }

    private void enqueueControlMessage(byte[] msg) {
        if (!controlQueue.offer(msg)) {
            controlQueue.poll();
            controlQueue.offer(msg);
        }
    }

    private void startConnection() {
        int attempts = 50;
        while (attempts != 0 && letServiceRunning.get()) {
            AdbConnection adbConnection = null;
            AdbStream videoStream = null;
            AdbStream audioStream = null;
            AdbStream controlStream = null;
            DataInputStream videoInputStream = null;
            DataInputStream audioInputStream = null;
            DataInputStream controlInputStream = null;
            DataOutputStream controlOutputStream = null;
            Thread controlSender = null;
            Thread controlReceiver = null;
            Thread audioReceiver = null;
            try {
                adbConnection = openAdbConnection(serverAdr);
                String socketService = "localabstract:" + getSocketName(scid);

                videoStream = adbConnection.open(socketService);
                videoInputStream = new DataInputStream(new AdbStreamInputStream(videoStream));
                int dummyByte = videoInputStream.read();
                if (dummyByte == -1) {
                    throw new EOFException("Could not read dummy byte from video stream");
                }

                audioStream = adbConnection.open(socketService);
                audioInputStream = new DataInputStream(new AdbStreamInputStream(audioStream));

                controlStream = adbConnection.open(socketService);
                controlInputStream = new DataInputStream(new AdbStreamInputStream(controlStream));
                controlOutputStream = new DataOutputStream(new AdbStreamOutputStream(controlStream));

                readDeviceMeta(videoInputStream);

                controlSender = startControlSender(controlOutputStream);
                controlReceiver = startControlReceiver(controlInputStream);
                audioReceiver = startAudioReceiver(audioInputStream);

                attempts = 0;
                socketStatus = true;
                notifyConnectionStateChanged(true);

                int codec = videoInputStream.readInt();
                String videoMimeType = resolveVideoMime(codec);
                if (codec == VIDEO_CODEC_MJPEG) {
                    stopVideoDecoder();
                    receiveMjpegStream(videoInputStream);
                    continue;
                }
                if (videoMimeType == null) {
                    throw new IOException("Unsupported video codec id: 0x" + Integer.toHexString(codec));
                }
                ensureVideoDecoder();
                streamWidth = videoInputStream.readInt();
                streamHeight = videoInputStream.readInt();
                notifyVideoSizeChanged(streamWidth, streamHeight);
                Log.i("scrcpy", "Video codec: 0x" + Integer.toHexString(codec) + " (" + videoMimeType + "), size: "
                        + streamWidth + "x" + streamHeight);

                boolean mustMergeConfigPacket = VIDEO_MIME_AVC.equals(videoMimeType) || VIDEO_MIME_HEVC.equals(videoMimeType);
                boolean decoderConfigured = false;
                byte[] codecConfigPacket = null;
                byte[] pendingMergeConfigPacket = null;
                while (letServiceRunning.get()) {
                    long ptsFlags = videoInputStream.readLong();
                    int packetSize = videoInputStream.readInt();
                    if (packetSize <= 0) {
                        continue;
                    }

                    byte[] packet = new byte[packetSize];
                    videoInputStream.readFully(packet);

                    boolean config = (ptsFlags & PACKET_FLAG_CONFIG) != 0;
                    boolean keyFrame = (ptsFlags & PACKET_FLAG_KEY_FRAME) != 0;
                    long pts = ptsFlags & ~(PACKET_FLAG_CONFIG | PACKET_FLAG_KEY_FRAME);

                    if (config) {
                        codecConfigPacket = packet;
                        if (mustMergeConfigPacket) {
                            pendingMergeConfigPacket = packet;
                        }
                    }

                    if ((updateAvailable.get() || !decoderConfigured)
                            && codecConfigPacket != null && surface != null) {
                        try {
                            updateAvailable.set(false);
                            configureVideoDecoder(videoMimeType, codecConfigPacket);
                            decoderConfigured = true;
                        } catch (RuntimeException e) {
                            decoderConfigured = false;
                        }
                    }

                    if (!decoderConfigured || surface == null) {
                        continue;
                    }

                    if (config) {
                        if (!mustMergeConfigPacket) {
                            videoDecoder.decodeSample(packet, 0, packet.length, pts, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                        }
                        continue;
                    }

                    byte[] packetToDecode = packet;
                    if (mustMergeConfigPacket && pendingMergeConfigPacket != null) {
                        packetToDecode = mergeConfigPacket(pendingMergeConfigPacket, packet);
                        pendingMergeConfigPacket = null;
                    }

                    int flags = keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    videoDecoder.decodeSample(packetToDecode, 0, packetToDecode.length, pts, flags);
                }
            } catch (EOFException e) {
                Log.i("scrcpy", "Connection closed");
                notifyConnectionStateChanged(false);
                if (attempts == 0) {
                    break;
                }
                attempts--;
                if (attempts == 0) {
                    socketStatus = false;
                    return;
                }
                sleepBeforeRetry();
            } catch (IOException e) {
                attempts--;
                if (attempts == 0) {
                    notifyConnectionError(e.getMessage());
                    socketStatus = false;
                    notifyConnectionStateChanged(false);
                    return;
                }
                sleepBeforeRetry();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notifyConnectionStateChanged(false);
                break;
            } finally {
                stopThread(controlSender);
                stopThread(controlReceiver);
                stopThread(audioReceiver);
                closeQuietly(controlOutputStream);
                closeQuietly(controlInputStream);
                closeQuietly(audioInputStream);
                closeQuietly(videoInputStream);
                closeQuietly(controlStream);
                closeQuietly(audioStream);
                closeQuietly(videoStream);
                closeQuietly(adbConnection);
                joinThread(controlSender);
                joinThread(controlReceiver);
                joinThread(audioReceiver);
            }
        }
        socketStatus = false;
        notifyConnectionStateChanged(false);
    }

    private AdbConnection openAdbConnection(String host) throws IOException, InterruptedException {
        AdbCrypto crypto = setupCrypto();
        java.net.Socket socket = new java.net.Socket(host, 5555);
        try {
            AdbConnection connection = AdbConnection.create(socket, crypto);
            connection.connect();
            return connection;
        } catch (IOException | InterruptedException e) {
            try {
                socket.close();
            } catch (IOException ignore) {
                // ignore
            }
            throw e;
        }
    }

    private AdbCrypto setupCrypto() throws IOException {
        AdbCrypto crypto;
        try {
            crypto = AdbCrypto.loadAdbKeyPair(SendCommands.getBase64Impl(), getFileStreamPath("priv.key"), getFileStreamPath("pub.key"));
        } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException | NullPointerException e) {
            crypto = null;
        }

        if (crypto == null) {
            try {
                crypto = AdbCrypto.generateAdbKeyPair(SendCommands.getBase64Impl());
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("Failed to generate adb key pair", e);
            }
            crypto.saveAdbKeyPair(getFileStreamPath("priv.key"), getFileStreamPath("pub.key"));
        }
        return crypto;
    }

    private static String getSocketName(int scid) {
        if (scid == -1) {
            return SOCKET_NAME_PREFIX;
        }
        return SOCKET_NAME_PREFIX + String.format("_%08x", scid);
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
    }

    private void readDeviceMeta(DataInputStream videoInputStream) throws IOException {
        byte[] nameBuffer = new byte[DEVICE_NAME_FIELD_LENGTH];
        videoInputStream.readFully(nameBuffer);
        int len = 0;
        while (len < DEVICE_NAME_FIELD_LENGTH && nameBuffer[len] != 0) {
            len++;
        }
        String deviceName = new String(nameBuffer, 0, len, StandardCharsets.UTF_8);
        if (!TextUtils.isEmpty(deviceName)) {
            Log.i("scrcpy", "Connected device: " + deviceName);
        }
    }

    private Thread startControlSender(DataOutputStream controlOutputStream) {
        Thread thread = new Thread(() -> {
            while (letServiceRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] msg = controlQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        controlOutputStream.write(msg);
                        controlOutputStream.flush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    break;
                }
            }
        }, "scrcpy-control-send");
        thread.start();
        return thread;
    }

    private Thread startControlReceiver(DataInputStream controlInputStream) {
        Thread thread = new Thread(() -> {
            while (letServiceRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    int type = controlInputStream.readUnsignedByte();
                    switch (type) {
                        case DEVICE_MSG_TYPE_CLIPBOARD:
                            readRemoteClipboard(controlInputStream);
                            break;
                        case DEVICE_MSG_TYPE_ACK_CLIPBOARD:
                            controlInputStream.readLong();
                            break;
                        case DEVICE_MSG_TYPE_UHID_OUTPUT:
                            controlInputStream.readUnsignedShort();
                            int size = controlInputStream.readUnsignedShort();
                            skipFully(controlInputStream, size);
                            break;
                        default:
                            throw new IOException("Unknown device message type: " + type);
                    }
                } catch (EOFException e) {
                    break;
                } catch (IOException e) {
                    break;
                }
            }
        }, "scrcpy-control-recv");
        thread.start();
        return thread;
    }

    private Thread startAudioReceiver(DataInputStream audioInputStream) {
        Thread thread = new Thread(() -> {
            try {
                int codec = audioInputStream.readInt();
                if (codec == AUDIO_STREAM_DISABLED) {
                    Log.i("scrcpy", "Remote audio stream disabled by device");
                    return;
                }
                if (codec == AUDIO_STREAM_ERROR) {
                    Log.w("scrcpy", "Remote audio stream unavailable due to server error");
                    return;
                }
                if (codec == AUDIO_CODEC_RAW) {
                    AudioTrack audioTrack = createAudioTrack(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
                    if (audioTrack == null) {
                        return;
                    }
                    try {
                        while (letServiceRunning.get() && !Thread.currentThread().isInterrupted()) {
                            long ptsFlags = audioInputStream.readLong();
                            int packetSize = audioInputStream.readInt();
                            if (packetSize <= 0) {
                                continue;
                            }

                            byte[] packet = new byte[packetSize];
                            audioInputStream.readFully(packet);

                            boolean config = (ptsFlags & PACKET_FLAG_CONFIG) != 0;
                            if (config) {
                                continue;
                            }
                            playAudioPacket(audioTrack, packet);
                        }
                    } finally {
                        releaseAudioTrack(audioTrack);
                    }
                    return;
                }

                String audioMimeType = resolveAudioMime(codec);
                if (audioMimeType == null) {
                    Log.w("scrcpy", "Unsupported audio codec id: 0x" + Integer.toHexString(codec));
                    return;
                }

                decodeCompressedAudio(audioInputStream, audioMimeType);
            } catch (EOFException e) {
                Log.i("scrcpy", "Audio stream closed");
            } catch (IOException e) {
                Log.w("scrcpy", "Audio receiver stopped: " + e.getMessage());
            }
        }, "scrcpy-audio-recv");
        thread.start();
        return thread;
    }

    private static AudioTrack createAudioTrack(int sampleRate, int channelCount) {
        int channelConfig = channelCount <= 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AUDIO_ENCODING);
        if (minBufferSize <= 0) {
            minBufferSize = sampleRate / 10;
        }

        AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                AUDIO_ENCODING,
                minBufferSize * 2,
                AudioTrack.MODE_STREAM
        );

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            Log.e("scrcpy", "Failed to initialize AudioTrack");
            return null;
        }
        track.play();
        return track;
    }

    private void playAudioPacket(AudioTrack audioTrack, byte[] packet) {
        int offset = 0;
        while (letServiceRunning.get() && offset < packet.length) {
            int written = audioTrack.write(packet, offset, packet.length - offset);
            if (written <= 0) {
                return;
            }
            offset += written;
        }
    }

    private static void releaseAudioTrack(AudioTrack audioTrack) {
        if (audioTrack == null) {
            return;
        }
        try {
            audioTrack.pause();
        } catch (IllegalStateException ignore) {
            // ignore
        }
        try {
            audioTrack.flush();
        } catch (IllegalStateException ignore) {
            // ignore
        }
        audioTrack.release();
    }

    private void configureVideoDecoder(String videoMimeType, byte[] codecConfigPacket) {
        ensureVideoDecoder();
        if (videoDecoder == null) {
            return;
        }

        ByteBuffer csd0;
        ByteBuffer csd1 = null;

        if (VIDEO_MIME_AVC.equals(videoMimeType)) {
            int firstStartCode = findAnnexBStartCode(codecConfigPacket, 0);
            int secondStartCode = findAnnexBStartCode(codecConfigPacket, firstStartCode >= 0 ? firstStartCode + 4 : 0);
            if (firstStartCode >= 0 && secondStartCode > firstStartCode) {
                csd0 = ByteBuffer.wrap(codecConfigPacket, firstStartCode, secondStartCode - firstStartCode);
                csd1 = ByteBuffer.wrap(codecConfigPacket, secondStartCode, codecConfigPacket.length - secondStartCode);
            } else {
                // Fallback to single CSD buffer if splitting failed.
                csd0 = ByteBuffer.wrap(codecConfigPacket);
            }
        } else {
            csd0 = ByteBuffer.wrap(codecConfigPacket);
        }

        int decodeWidth = streamWidth > 0 ? streamWidth : screenWidth;
        int decodeHeight = streamHeight > 0 ? streamHeight : screenHeight;
        videoDecoder.configure(surface, decodeWidth, decodeHeight, videoMimeType, csd0, csd1);
    }

    private void ensureVideoDecoder() {
        if (videoDecoder != null) {
            return;
        }
        videoDecoder = new VideoDecoder();
        videoDecoder.setVideoSizeListener(this::notifyVideoSizeChanged);
        videoDecoder.start();
    }

    private void stopVideoDecoder() {
        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder = null;
        }
    }

    private void receiveMjpegStream(DataInputStream videoInputStream) throws IOException {
        int initialWidth = videoInputStream.readInt();
        int initialHeight = videoInputStream.readInt();
        notifyVideoSizeChanged(initialWidth, initialHeight);

        while (letServiceRunning.get() && !Thread.currentThread().isInterrupted()) {
            long ptsFlags = videoInputStream.readLong();
            int packetSize = videoInputStream.readInt();
            if (packetSize <= MJPEG_PACKET_HEADER_BYTES) {
                if (packetSize > 0) {
                    skipFully(videoInputStream, packetSize);
                }
                continue;
            }

            byte[] packet = new byte[packetSize];
            videoInputStream.readFully(packet);

            boolean config = (ptsFlags & PACKET_FLAG_CONFIG) != 0;
            if (config) {
                continue;
            }

            int frameWidth = readIntBE(packet, 0);
            int frameHeight = readIntBE(packet, 4);
            if (frameWidth <= 0 || frameHeight <= 0) {
                continue;
            }

            if (frameWidth != streamWidth || frameHeight != streamHeight) {
                notifyVideoSizeChanged(frameWidth, frameHeight);
            }

            Bitmap bitmap = BitmapFactory.decodeByteArray(packet, MJPEG_PACKET_HEADER_BYTES, packet.length - MJPEG_PACKET_HEADER_BYTES);
            if (bitmap == null) {
                continue;
            }

            try {
                drawBitmapFrame(bitmap);
            } finally {
                bitmap.recycle();
            }
        }
    }

    private void drawBitmapFrame(Bitmap bitmap) {
        Surface targetSurface = surface;
        if (targetSurface == null || !targetSurface.isValid()) {
            return;
        }

        Canvas canvas = null;
        try {
            canvas = targetSurface.lockCanvas(null);
            int canvasWidth = canvas.getWidth();
            int canvasHeight = canvas.getHeight();
            if (canvasWidth <= 0 || canvasHeight <= 0) {
                return;
            }

            float srcAspect = bitmap.getWidth() / (float) bitmap.getHeight();
            int targetWidth = canvasWidth;
            int targetHeight = Math.round(targetWidth / srcAspect);
            if (targetHeight > canvasHeight) {
                targetHeight = canvasHeight;
                targetWidth = Math.round(targetHeight * srcAspect);
            }
            int left = (canvasWidth - targetWidth) / 2;
            int top = (canvasHeight - targetHeight) / 2;
            imageDstRect.set(left, top, left + targetWidth, top + targetHeight);

            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(bitmap, null, imageDstRect, imagePaint);
        } catch (RuntimeException e) {
            // Surface may become invalid during lifecycle transitions.
        } finally {
            if (canvas != null) {
                try {
                    targetSurface.unlockCanvasAndPost(canvas);
                } catch (RuntimeException e) {
                    // ignore
                }
            }
        }
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static int findAnnexBStartCode(byte[] data, int from) {
        if (data == null || data.length < 4) {
            return -1;
        }
        int start = Math.max(0, from);
        for (int i = start; i <= data.length - 4; i++) {
            if (data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x00 && data[i + 3] == 0x01) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] mergeConfigPacket(byte[] configPacket, byte[] packet) {
        byte[] merged = new byte[configPacket.length + packet.length];
        System.arraycopy(configPacket, 0, merged, 0, configPacket.length);
        System.arraycopy(packet, 0, merged, configPacket.length, packet.length);
        return merged;
    }

    private void notifyVideoSizeChanged(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        streamWidth = width;
        streamHeight = height;
        remoteDevResolution[0] = width;
        remoteDevResolution[1] = height;

        ServiceCallbacks callbacks = serviceCallbacks;
        if (callbacks != null) {
            mainHandler.post(() -> callbacks.onVideoSizeChanged(width, height));
        }
    }

    private void notifyConnectionStateChanged(boolean connected) {
        boolean previous = connectionReported.getAndSet(connected);
        if (previous == connected) {
            return;
        }
        ServiceCallbacks callbacks = serviceCallbacks;
        if (callbacks != null) {
            mainHandler.post(() -> callbacks.onConnectionStateChanged(connected));
        }
    }

    private void notifyConnectionError(String message) {
        ServiceCallbacks callbacks = serviceCallbacks;
        if (callbacks == null) {
            return;
        }
        String nonNull = message == null ? "" : message;
        mainHandler.post(() -> callbacks.onConnectionError(nonNull));
    }

    private String resolveVideoMime(int codecId) {
        switch (codecId) {
            case VIDEO_CODEC_H264:
                return VIDEO_MIME_AVC;
            case VIDEO_CODEC_H265:
                return VIDEO_MIME_HEVC;
            case VIDEO_CODEC_AV1:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return VIDEO_MIME_AV1;
                }
                Log.w("scrcpy", "AV1 not supported on Android API " + Build.VERSION.SDK_INT);
                return null;
            case VIDEO_CODEC_MJPEG:
                return null;
            default:
                return null;
        }
    }

    private static String resolveAudioMime(int codecId) {
        switch (codecId) {
            case AUDIO_CODEC_AAC:
                return AUDIO_MIME_AAC;
            case AUDIO_CODEC_OPUS:
                return AUDIO_MIME_OPUS;
            case AUDIO_CODEC_FLAC:
                return AUDIO_MIME_FLAC;
            default:
                return null;
        }
    }

    private void decodeCompressedAudio(DataInputStream audioInputStream, String audioMimeType) throws IOException {
        MediaCodec decoder = null;
        AudioTrack audioTrack = null;
        boolean started = false;

        try {
            decoder = MediaCodec.createDecoderByType(audioMimeType);
            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();

            while (letServiceRunning.get() && !Thread.currentThread().isInterrupted()) {
                long ptsFlags = audioInputStream.readLong();
                int packetSize = audioInputStream.readInt();
                if (packetSize <= 0) {
                    continue;
                }

                byte[] packet = new byte[packetSize];
                audioInputStream.readFully(packet);

                boolean config = (ptsFlags & PACKET_FLAG_CONFIG) != 0;
                long pts = ptsFlags & ~(PACKET_FLAG_CONFIG | PACKET_FLAG_KEY_FRAME);
                int inputFlags = config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;

                if (!started) {
                    MediaFormat format = MediaFormat.createAudioFormat(audioMimeType, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
                    if (config) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(packet));
                    }
                    decoder.configure(format, null, null, 0);
                    decoder.start();
                    started = true;

                    // If this first packet is only codec config, it has already been applied via MediaFormat.
                    if (config) {
                        drainAudioDecoder(decoder, outputInfo, null);
                        continue;
                    }
                }

                queueAudioDecoderInput(decoder, packet, pts, inputFlags);
                audioTrack = drainAudioDecoder(decoder, outputInfo, audioTrack);
            }
        } catch (RuntimeException e) {
            Log.w("scrcpy", "Compressed audio decode stopped: " + e.getMessage());
        } finally {
            if (decoder != null) {
                try {
                    if (started) {
                        decoder.stop();
                    }
                } catch (IllegalStateException ignore) {
                    // ignore
                }
                decoder.release();
            }
            releaseAudioTrack(audioTrack);
        }
    }

    private void queueAudioDecoderInput(MediaCodec decoder, byte[] packet, long pts, int flags) {
        int inputIndex;
        try {
            inputIndex = decoder.dequeueInputBuffer(10000);
        } catch (IllegalStateException e) {
            return;
        }
        if (inputIndex < 0) {
            return;
        }

        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
        if (inputBuffer == null) {
            return;
        }
        inputBuffer.clear();
        inputBuffer.put(packet);
        decoder.queueInputBuffer(inputIndex, 0, packet.length, pts, flags);
    }

    private AudioTrack drainAudioDecoder(MediaCodec decoder, MediaCodec.BufferInfo outputInfo, AudioTrack currentTrack) {
        while (letServiceRunning.get() && !Thread.currentThread().isInterrupted()) {
            int outputIndex;
            try {
                outputIndex = decoder.dequeueOutputBuffer(outputInfo, 0);
            } catch (IllegalStateException e) {
                return currentTrack;
            }

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return currentTrack;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = decoder.getOutputFormat();
                int sampleRate = outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        : AUDIO_SAMPLE_RATE;
                int channelCount = outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        : AUDIO_CHANNELS;
                releaseAudioTrack(currentTrack);
                currentTrack = createAudioTrack(sampleRate, channelCount);
                continue;
            }

            if (outputIndex < 0) {
                return currentTrack;
            }

            ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
            if (outputBuffer != null && outputInfo.size > 0) {
                if (currentTrack == null) {
                    MediaFormat outputFormat = decoder.getOutputFormat();
                    int sampleRate = outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            : AUDIO_SAMPLE_RATE;
                    int channelCount = outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            : AUDIO_CHANNELS;
                    currentTrack = createAudioTrack(sampleRate, channelCount);
                }

                if (currentTrack != null) {
                    outputBuffer.position(outputInfo.offset);
                    outputBuffer.limit(outputInfo.offset + outputInfo.size);
                    byte[] pcm = new byte[outputInfo.size];
                    outputBuffer.get(pcm);
                    playAudioPacket(currentTrack, pcm);
                }
            }
            decoder.releaseOutputBuffer(outputIndex, false);
        }
        return currentTrack;
    }

    private void readRemoteClipboard(DataInputStream controlInputStream) throws IOException {
        int len = controlInputStream.readInt();
        if (len < 0 || len > DEVICE_CLIPBOARD_TEXT_MAX_LENGTH) {
            throw new IOException("Invalid clipboard message length: " + len);
        }

        byte[] data = new byte[len];
        controlInputStream.readFully(data);
        String text = new String(data, StandardCharsets.UTF_8);
        applyRemoteClipboard(text);
    }

    private void applyRemoteClipboard(String text) {
        if (clipboardManager == null) {
            return;
        }

        lastRemoteClipboardText.set(text);
        mainHandler.post(() -> {
            if (clipboardManager == null) {
                return;
            }
            String currentText = readLocalClipboardText();
            if (TextUtils.equals(currentText, text)) {
                return;
            }
            ClipData clipData = ClipData.newPlainText("scrcpy", text);
            clipboardManager.setPrimaryClip(clipData);
        });
    }

    private void registerClipboardSync() {
        if (clipboardListenerRegistered.get()) {
            return;
        }

        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Log.w("scrcpy", "ClipboardManager unavailable");
            return;
        }

        clipboardListener = () -> {
            String text = readLocalClipboardText();
            if (text == null) {
                return;
            }

            String remoteText = lastRemoteClipboardText.getAndSet(null);
            if (remoteText != null && remoteText.equals(text)) {
                return;
            }

            enqueueControlMessage(buildSetClipboardControlMessage(clipboardSequence.getAndIncrement(), text, false));
        };

        clipboardManager.addPrimaryClipChangedListener(clipboardListener);
        clipboardListenerRegistered.set(true);
    }

    private void unregisterClipboardSync() {
        if (!clipboardListenerRegistered.getAndSet(false)) {
            return;
        }
        if (clipboardManager != null && clipboardListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener);
        }
        clipboardListener = null;
    }

    private String readLocalClipboardText() {
        if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
            return null;
        }

        ClipData clipData = clipboardManager.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            return null;
        }

        CharSequence text = clipData.getItemAt(0).coerceToText(this);
        if (text == null) {
            return null;
        }
        return text.toString();
    }

    private static byte[] buildKeyControlMessage(int action, int keycode) {
        byte[] msg = new byte[14];
        msg[0] = CONTROL_MSG_TYPE_INJECT_KEYCODE;
        msg[1] = (byte) action;
        writeIntBE(msg, 2, keycode);
        writeIntBE(msg, 6, 0); // repeat
        writeIntBE(msg, 10, 0); // metastate
        return msg;
    }

    private static byte[] buildTouchControlMessage(int action, long pointerId, int x, int y, int screenW, int screenH, float pressure,
            int actionButton, int buttons) {
        byte[] msg = new byte[32];
        msg[0] = CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT;
        msg[1] = (byte) action;
        writeLongBE(msg, 2, pointerId);
        writeIntBE(msg, 10, x);
        writeIntBE(msg, 14, y);
        writeShortBE(msg, 18, screenW);
        writeShortBE(msg, 20, screenH);
        writeShortBE(msg, 22, floatToU16FixedPoint(pressure));
        writeIntBE(msg, 24, actionButton);
        writeIntBE(msg, 28, buttons);
        return msg;
    }

    private static byte[] buildSetClipboardControlMessage(long sequence, String text, boolean paste) {
        byte[] raw = getClippedUtf8(text, CONTROL_CLIPBOARD_TEXT_MAX_LENGTH);
        byte[] msg = new byte[14 + raw.length];
        msg[0] = CONTROL_MSG_TYPE_SET_CLIPBOARD;
        writeLongBE(msg, 1, sequence);
        msg[9] = (byte) (paste ? 1 : 0);
        writeIntBE(msg, 10, raw.length);
        System.arraycopy(raw, 0, msg, 14, raw.length);
        return msg;
    }

    private static byte[] getClippedUtf8(String text, int maxBytes) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return raw;
        }

        int len = maxBytes;
        while (len > 0 && (raw[len] & 0xC0) == 0x80) {
            --len;
        }
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, 0, len);
        return out;
    }

    private static int floatToU16FixedPoint(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (clamped >= 1f) {
            return 0xFFFF;
        }
        return (int) (clamped * 65536f) & 0xFFFF;
    }

    private static void writeIntBE(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static void writeShortBE(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeLongBE(byte[] data, int offset, long value) {
        data[offset] = (byte) ((value >>> 56) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 48) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 40) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 32) & 0xFF);
        data[offset + 4] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 5] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 6] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 7] = (byte) (value & 0xFF);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignore) {
            // ignore
        }
    }

    private static final class AdbStreamInputStream extends InputStream {
        private final AdbStream stream;
        private byte[] currentBuffer = new byte[0];
        private int offset;
        private boolean closed;

        private AdbStreamInputStream(AdbStream stream) {
            this.stream = stream;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int len = read(single, 0, 1);
            if (len <= 0) {
                return -1;
            }
            return single[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Adb stream already closed");
            }
            if (len == 0) {
                return 0;
            }
            if (offset >= currentBuffer.length && !refill()) {
                return -1;
            }
            int count = Math.min(len, currentBuffer.length - offset);
            System.arraycopy(currentBuffer, offset, b, off, count);
            offset += count;
            return count;
        }

        private boolean refill() throws IOException {
            while (!closed) {
                byte[] chunk;
                try {
                    chunk = stream.read();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading from adb stream", e);
                }
                if (chunk != null && chunk.length > 0) {
                    currentBuffer = chunk;
                    offset = 0;
                    return true;
                }
                if (stream.isClosed()) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            stream.close();
        }
    }

    private static final class AdbStreamOutputStream extends OutputStream {
        private final AdbStream stream;
        private boolean closed;

        private AdbStreamOutputStream(AdbStream stream) {
            this.stream = stream;
        }

        @Override
        public void write(int b) throws IOException {
            byte[] single = new byte[]{(byte) b};
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Adb stream already closed");
            }
            if (len <= 0) {
                return;
            }
            byte[] payload;
            if (off == 0 && len == b.length) {
                payload = b;
            } else {
                payload = new byte[len];
                System.arraycopy(b, off, payload, 0, len);
            }

            try {
                stream.write(payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while writing to adb stream", e);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            stream.close();
        }
    }

    private static void skipFully(DataInputStream input, int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            int skipped = input.skipBytes(remaining);
            if (skipped <= 0) {
                throw new EOFException("Unexpected EOF while skipping " + bytes + " bytes");
            }
            remaining -= skipped;
        }
    }

    private static void stopThread(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void joinThread(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onDestroy() {
        letServiceRunning.set(false);
        unregisterClipboardSync();
        if (connectionThread != null) {
            connectionThread.interrupt();
        }
        stopVideoDecoder();
        super.onDestroy();
    }

    public interface ServiceCallbacks {
        void onVideoSizeChanged(int width, int height);

        void onConnectionStateChanged(boolean connected);

        void onConnectionError(String message);
    }

    public class MyServiceBinder extends Binder {
        public Scrcpy getService() {
            return Scrcpy.this;
        }
    }
}
