package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.ConfigurationException;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.CodecOption;
import com.genymobile.scrcpy.util.CodecUtils;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AmlogicV4l2CaptureProcessor implements AsyncProcessor {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10;
    private static final int DEFAULT_FRAME_RATE = 30;
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";
    private static final long AUTO_RESET_STALL_MS = 3000;
    private static final long AUTO_RESET_STARTUP_STALL_MS = 8000;
    private static final int MAX_CAPTURE_QUEUE_DRAIN = 8;
    private static final long LATE_FRAME_DROP_THRESHOLD_US = 250_000;
    private static final long LATE_FRAME_RECOVER_THRESHOLD_US = 120_000;
    private static final long SYNC_FRAME_REQUEST_INTERVAL_MS = 500;
    private static final long FORCE_RECOVER_DROP_COUNT = 120;

    private final Streamer streamer;
    private final Options options;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean resetRequested = new AtomicBoolean();

    private Thread thread;
    private boolean droppingFrames;
    private long droppedFrameCount;
    private long lastSyncFrameRequestMs;
    private long driftBasePtsUs = Long.MIN_VALUE;
    private long driftBaseNowUs;

    public AmlogicV4l2CaptureProcessor(Streamer streamer, Options options) {
        this.streamer = streamer;
        this.options = options;
    }

    private void streamCapture() throws IOException, ConfigurationException {
        List<String> devicePaths = resolveDevicePaths(options);
        Ln.i("Amlogic V4L2 device fallback " + (options.getAmlogicV4l2FallbackDevice() ? "enabled" : "disabled")
                + ", strictCaps=" + options.getAmlogicV4l2StrictCaps()
                + ", candidates=" + devicePaths);
        while (!stopped.get()) {
            IOException lastOpenError = null;
            boolean reset = false;
            for (int i = 0; i < devicePaths.size(); ++i) {
                String devicePath = devicePaths.get(i);
                Ln.i("Trying Amlogic V4L2 device: " + devicePath + " (candidate " + (i + 1) + "/" + devicePaths.size() + ")");
                try {
                    streamCaptureFromDevice(devicePath);
                    if (stopped.get()) {
                        return;
                    }
                    if (resetRequested.getAndSet(false)) {
                        Ln.i("Amlogic V4L2 video reset requested");
                        reset = true;
                        break;
                    }
                    return;
                } catch (IOException e) {
                    if (stopped.get() || isBrokenPipe(e)) {
                        Ln.d("Amlogic V4L2 stream closed: " + e.getMessage());
                        return;
                    }
                    lastOpenError = e;
                    if (i + 1 < devicePaths.size()) {
                        Ln.w("Failed to open Amlogic V4L2 device " + devicePath + ": " + e.getMessage() + ". Trying next candidate.");
                    } else {
                        Ln.e("Failed to open Amlogic V4L2 device " + devicePath + ": " + e.getMessage());
                    }
                }
            }

            if (reset) {
                continue;
            }

            if (lastOpenError != null) {
                throw lastOpenError;
            }
            throw new IOException("No available Amlogic V4L2 device candidates");
        }
    }

    private void streamCaptureFromDevice(String devicePath) throws IOException, ConfigurationException {
        try (AmlogicV4l2CaptureNative capture = AmlogicV4l2CaptureNative.open(devicePath,
                options.getAmlogicV4l2Width(), options.getAmlogicV4l2Height(), options.getAmlogicV4l2Fps(),
                options.getAmlogicV4l2PortType(), options.getAmlogicV4l2SourceType(), options.getAmlogicV4l2Mode(),
                options.getAmlogicV4l2Rotation(),
                options.getAmlogicV4l2CropLeft(), options.getAmlogicV4l2CropTop(), options.getAmlogicV4l2CropWidth(),
                options.getAmlogicV4l2CropHeight(), options.getAmlogicV4l2ReqBufCount(), options.getAmlogicV4l2PixelFormat(),
                options.getAmlogicV4l2StrictCaps())) {
            Size streamSize = capture.getSize();
            streamer.writeVideoHeader(streamSize);

            int pixelFormat = capture.getPixelFormat();
            Ln.i("Amlogic V4L2 mode started: " + devicePath
                    + " -> " + streamSize.getWidth() + "x" + streamSize.getHeight()
                    + ", fmt=0x" + Integer.toHexString(pixelFormat)
                    + " (" + fourccToString(pixelFormat) + ")");

            if (pixelFormat == AmlogicV4l2CaptureNative.PIXEL_FORMAT_H264) {
                streamH264Passthrough(capture);
            } else {
                streamRawWithEncoder(capture);
            }
        }
    }

    private void streamH264Passthrough(AmlogicV4l2CaptureNative capture) throws IOException {
        ByteBuffer frameBuffer = ByteBuffer.allocateDirect(Math.max(capture.getFrameCapacity(), 1));
        AmlogicV4l2CaptureNative.FrameMetadata metadata = new AmlogicV4l2CaptureNative.FrameMetadata();
        boolean configSent = false;
        boolean hasMediaFrame = false;
        long lastFrameTsMs = System.currentTimeMillis();
        resetDropState();

        while (!stopped.get() && !resetRequested.get()) {
            frameBuffer.clear();
            int packetSize = capture.readFrame(frameBuffer, metadata);
            if (packetSize == AmlogicV4l2CaptureNative.EAGAIN) {
                if (checkAndRequestResetOnStall("h264", hasMediaFrame, lastFrameTsMs)) {
                    break;
                }
                continue;
            }
            if (packetSize <= 0) {
                if (checkAndRequestResetOnStall("h264", hasMediaFrame, lastFrameTsMs)) {
                    break;
                }
                continue;
            }
            hasMediaFrame = true;
            lastFrameTsMs = System.currentTimeMillis();
            packetSize = keepLatestFrame(capture, frameBuffer, metadata, packetSize, "h264");

            try {
                frameBuffer.position(0);
                frameBuffer.limit(packetSize);

                byte[] frameCopy = null;
                if (!configSent || !metadata.keyFrame) {
                    frameCopy = toArray(frameBuffer);
                }
                if (!metadata.keyFrame && frameCopy != null) {
                    metadata.keyFrame = containsAvcNalType(frameCopy, 5);
                }

                if (!configSent) {
                    if (!metadata.keyFrame) {
                        // Wait for a key frame carrying SPS/PPS before sending media packets.
                        continue;
                    }

                    byte[] avcConfig = extractAvcConfig(frameCopy);
                    if (avcConfig == null) {
                        continue;
                    }

                    streamer.writePacket(ByteBuffer.wrap(avcConfig), metadata.ptsUs, true, true);
                    configSent = true;
                }

                frameBuffer.position(0);
                frameBuffer.limit(packetSize);
                if (shouldDropFrame(null, metadata.ptsUs, metadata.keyFrame, "h264")) {
                    continue;
                }
                streamer.writePacket(frameBuffer, metadata.ptsUs, false, metadata.keyFrame);
            } finally {
                capture.releaseFrame(metadata);
            }
        }
    }

    private void streamRawWithEncoder(AmlogicV4l2CaptureNative capture) throws IOException, ConfigurationException {
        Size size = capture.getSize();
        int width = size.getWidth();
        int height = size.getHeight();
        int pixelFormat = capture.getPixelFormat();
        int stride = Math.max(capture.getBytesPerLine(), width);

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            int colorFormat = chooseColorFormat(codec);
            MediaFormat format = createAvcFormat(width, height, colorFormat, options.getVideoBitRate(), options.getMaxFps(),
                    options.getVideoCodecOptions());
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            resetDropState();

            Ln.i("Amlogic V4L2 raw path uses encoder: " + codec.getName() + ", colorFormat=0x" + Integer.toHexString(colorFormat)
                    + ", source=" + fourccToString(pixelFormat));

            ByteBuffer rawFrameBuffer = ByteBuffer.allocateDirect(Math.max(capture.getFrameCapacity(), 1));
            byte[] rawScratch = new byte[Math.max(capture.getFrameCapacity(), 1)];
            byte[] converted = new byte[width * height * 3 / 2];
            AmlogicV4l2CaptureNative.FrameMetadata metadata = new AmlogicV4l2CaptureNative.FrameMetadata();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int[] outputDebugCount = new int[]{0};
            long statNextLogMs = System.currentTimeMillis() + 1000;
            int statEagain = 0;
            int statZero = 0;
            int statPositive = 0;
            boolean hasMediaFrame = false;
            long lastFrameTsMs = System.currentTimeMillis();

            while (!stopped.get() && !resetRequested.get()) {
                rawFrameBuffer.clear();
                int rawSize = capture.readFrame(rawFrameBuffer, metadata);
                if (rawSize == AmlogicV4l2CaptureNative.EAGAIN) {
                    ++statEagain;
                    drainEncoder(codec, bufferInfo, outputDebugCount);
                    long now = System.currentTimeMillis();
                    if (checkAndRequestResetOnStall("raw", hasMediaFrame, lastFrameTsMs, now)) {
                        break;
                    }
                    if (now >= statNextLogMs) {
                        Ln.i("Amlogic raw stats: ok=" + statPositive + ", eagain=" + statEagain + ", zero=" + statZero);
                        statNextLogMs = now + 1000;
                    }
                    continue;
                }
                if (rawSize <= 0) {
                    ++statZero;
                    drainEncoder(codec, bufferInfo, outputDebugCount);
                    long now = System.currentTimeMillis();
                    if (checkAndRequestResetOnStall("raw", hasMediaFrame, lastFrameTsMs, now)) {
                        break;
                    }
                    if (now >= statNextLogMs) {
                        Ln.i("Amlogic raw stats: ok=" + statPositive + ", eagain=" + statEagain + ", zero=" + statZero);
                        statNextLogMs = now + 1000;
                    }
                    continue;
                }
                ++statPositive;
                hasMediaFrame = true;
                lastFrameTsMs = System.currentTimeMillis();
                rawSize = keepLatestFrame(capture, rawFrameBuffer, metadata, rawSize, "raw");
                if (statPositive <= 3) {
                    Ln.i("Amlogic raw frame #" + statPositive + ", size=" + rawSize + ", pts=" + metadata.ptsUs);
                }

                try {
                    int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int inputSize = convertRawFrame(rawFrameBuffer, rawSize, rawScratch, converted, pixelFormat, stride,
                                    width, height, colorFormat);
                            inputBuffer.put(converted, 0, inputSize);
                            codec.queueInputBuffer(inputIndex, 0, inputSize, metadata.ptsUs, 0);
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, metadata.ptsUs, 0);
                        }
                    }
                } finally {
                    capture.releaseFrame(metadata);
                }

                drainEncoder(codec, bufferInfo, outputDebugCount);
                long now = System.currentTimeMillis();
                if (now >= statNextLogMs) {
                    Ln.i("Amlogic raw stats: ok=" + statPositive + ", eagain=" + statEagain + ", zero=" + statZero);
                    statNextLogMs = now + 1000;
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (IllegalStateException e) {
                // ignore
            }
            codec.release();
        }
    }

    private boolean checkAndRequestResetOnStall(String path, boolean hasMediaFrame, long lastFrameTsMs) {
        return checkAndRequestResetOnStall(path, hasMediaFrame, lastFrameTsMs, System.currentTimeMillis());
    }

    private boolean checkAndRequestResetOnStall(String path, boolean hasMediaFrame, long lastFrameTsMs, long nowMs) {
        long threshold = hasMediaFrame ? AUTO_RESET_STALL_MS : AUTO_RESET_STARTUP_STALL_MS;
        long stallMs = nowMs - lastFrameTsMs;
        if (stallMs < threshold) {
            return false;
        }

        if (resetRequested.compareAndSet(false, true)) {
            Ln.w("Amlogic V4L2 " + path + " capture stalled for " + stallMs + "ms, request auto-reset");
        }
        return true;
    }

    private void drainEncoder(MediaCodec codec, MediaCodec.BufferInfo bufferInfo, int[] outputDebugCount) throws IOException {
        while (true) {
            if (stopped.get() || resetRequested.get()) {
                return;
            }
            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = codec.getOutputFormat();
                ByteBuffer csd = buildCodecConfigPacket(outputFormat);
                if (csd != null && csd.remaining() > 0) {
                    int csdSize = csd.remaining();
                    streamer.writePacket(csd, 0, true, false);
                    Ln.i("Amlogic encoder output format changed, sent codec config packet, size=" + csdSize);
                } else {
                    Ln.w("Amlogic encoder output format changed, but no codec config found");
                }
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            try {
                if (bufferInfo.size > 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null) {
                        if (outputDebugCount != null && outputDebugCount.length > 0 && outputDebugCount[0] < 8) {
                            boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            Ln.i("Amlogic encoder packet #" + (outputDebugCount[0] + 1)
                                    + ", size=" + bufferInfo.size
                                    + ", flags=0x" + Integer.toHexString(bufferInfo.flags)
                                    + ", config=" + config
                                    + ", key=" + keyFrame);
                            outputDebugCount[0] += 1;
                        }
                        boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (!config && shouldDropFrame(codec, bufferInfo.presentationTimeUs, keyFrame, "raw")) {
                            continue;
                        }
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        streamer.writePacket(outputBuffer, bufferInfo);
                    }
                }
            } finally {
                codec.releaseOutputBuffer(outputIndex, false);
            }
        }
    }

    private void resetDropState() {
        droppingFrames = false;
        droppedFrameCount = 0;
        lastSyncFrameRequestMs = 0;
        driftBasePtsUs = Long.MIN_VALUE;
        driftBaseNowUs = 0;
    }

    private int keepLatestFrame(AmlogicV4l2CaptureNative capture, ByteBuffer frameBuffer, AmlogicV4l2CaptureNative.FrameMetadata metadata,
            int currentSize, String path) throws IOException {
        int droppedQueued = 0;

        for (int i = 0; i < MAX_CAPTURE_QUEUE_DRAIN; ++i) {
            AmlogicV4l2CaptureNative.FrameMetadata next = new AmlogicV4l2CaptureNative.FrameMetadata();
            frameBuffer.clear();
            int nextSize = capture.readFrame(frameBuffer, next);
            if (nextSize <= 0) {
                break;
            }

            capture.releaseFrame(metadata);
            metadata.ptsUs = next.ptsUs;
            metadata.keyFrame = next.keyFrame;
            metadata.bufferIndex = next.bufferIndex;
            currentSize = nextSize;
            ++droppedQueued;
        }

        if (droppedQueued > 0) {
            Ln.d("Amlogic " + path + " dropped " + droppedQueued + " queued frame(s) to keep latest");
        }

        return currentSize;
    }

    private boolean shouldDropFrame(MediaCodec codec, long ptsUs, boolean keyFrame, String path) {
        long lateUs = computeLatencyDriftUs(ptsUs);

        long nowMs = SystemClock.elapsedRealtime();

        if (droppingFrames) {
            if (keyFrame && (lateUs <= LATE_FRAME_RECOVER_THRESHOLD_US || droppedFrameCount >= FORCE_RECOVER_DROP_COUNT)) {
                droppingFrames = false;
                Ln.i("Amlogic " + path + " latency recovered on key frame (late=" + (lateUs / 1_000) + "ms, dropped="
                        + droppedFrameCount + ")");
                droppedFrameCount = 0;
                return false;
            }

            ++droppedFrameCount;
            maybeRequestSyncFrame(codec, nowMs, path);
            if (droppedFrameCount == 1 || droppedFrameCount % 60 == 0) {
                Ln.w("Amlogic " + path + " dropping late frames (late=" + (lateUs / 1_000) + "ms, dropped=" + droppedFrameCount + ")");
            }
            return true;
        }

        if (lateUs > LATE_FRAME_DROP_THRESHOLD_US && !keyFrame) {
            droppingFrames = true;
            droppedFrameCount = 1;
            maybeRequestSyncFrame(codec, nowMs, path);
            Ln.w("Amlogic " + path + " is lagging (late=" + (lateUs / 1_000) + "ms), dropping until next key frame");
            return true;
        }

        return false;
    }

    private long computeLatencyDriftUs(long ptsUs) {
        long nowUs = System.nanoTime() / 1_000;
        if (driftBasePtsUs == Long.MIN_VALUE || ptsUs < driftBasePtsUs) {
            driftBasePtsUs = ptsUs;
            driftBaseNowUs = nowUs;
            return 0;
        }

        long elapsedPts = ptsUs - driftBasePtsUs;
        long elapsedNow = nowUs - driftBaseNowUs;
        long driftUs = elapsedNow - elapsedPts;
        return driftUs > 0 ? driftUs : 0;
    }

    private void maybeRequestSyncFrame(MediaCodec codec, long nowMs, String path) {
        if (codec == null) {
            return;
        }
        if (nowMs - lastSyncFrameRequestMs < SYNC_FRAME_REQUEST_INTERVAL_MS) {
            return;
        }

        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try {
            codec.setParameters(params);
            lastSyncFrameRequestMs = nowMs;
        } catch (IllegalStateException | IllegalArgumentException e) {
            Ln.d("Amlogic " + path + " could not request sync frame: " + e.getMessage());
        }
    }

    private static ByteBuffer buildCodecConfigPacket(MediaFormat format) {
        if (format == null) {
            return null;
        }

        ByteBuffer csd0 = format.getByteBuffer("csd-0");
        ByteBuffer csd1 = format.getByteBuffer("csd-1");
        if (csd0 == null && csd1 == null) {
            return null;
        }

        byte[] b0 = csd0 != null ? ensureAnnexBPrefix(toByteArray(csd0)) : null;
        byte[] b1 = csd1 != null ? ensureAnnexBPrefix(toByteArray(csd1)) : null;
        int len0 = b0 != null ? b0.length : 0;
        int len1 = b1 != null ? b1.length : 0;
        byte[] merged = new byte[len0 + len1];
        if (len0 > 0) {
            System.arraycopy(b0, 0, merged, 0, len0);
        }
        if (len1 > 0) {
            System.arraycopy(b1, 0, merged, len0, len1);
        }
        return ByteBuffer.wrap(merged);
    }

    private static byte[] ensureAnnexBPrefix(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        if (hasAnnexBPrefix(data)) {
            return data;
        }
        byte[] prefixed = new byte[data.length + 4];
        prefixed[0] = 0;
        prefixed[1] = 0;
        prefixed[2] = 0;
        prefixed[3] = 1;
        System.arraycopy(data, 0, prefixed, 4, data.length);
        return prefixed;
    }

    private static boolean hasAnnexBPrefix(byte[] data) {
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            return true;
        }
        return data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1;
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        ByteBuffer dup = buffer.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static MediaFormat createAvcFormat(int width, int height, int colorFormat, int bitRate, float maxFps, List<CodecOption> codecOptions) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, maxFps > 0 ? Math.round(maxFps) : DEFAULT_FRAME_RATE);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
        if (maxFps > 0) {
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                String key = option.getKey();
                Object value = option.getValue();
                CodecUtils.setCodecOption(format, key, value);
                Ln.d("Video codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
            }
        }
        return format;
    }

    private static int chooseColorFormat(MediaCodec codec) throws ConfigurationException {
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
        int[] preferred = {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        };
        for (int wanted : preferred) {
            for (int available : capabilities.colorFormats) {
                if (available == wanted) {
                    return wanted;
                }
            }
        }
        throw new ConfigurationException("No supported YUV420 input format on encoder: " + codec.getName());
    }

    private static int convertRawFrame(ByteBuffer rawBuffer, int rawSize, byte[] rawScratch, byte[] outYuv, int srcFourcc, int srcStride,
            int width, int height, int colorFormat) throws ConfigurationException {
        if (rawSize <= 0 || rawSize > rawScratch.length) {
            throw new ConfigurationException("Invalid raw frame size: " + rawSize);
        }

        rawBuffer.position(0);
        rawBuffer.limit(rawSize);
        rawBuffer.get(rawScratch, 0, rawSize);

        boolean dstPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
        int dstSize = width * height * 3 / 2;
        if (outYuv.length < dstSize) {
            throw new ConfigurationException("Converted buffer too small");
        }

        int effectiveStride = resolveInputStride(srcFourcc, srcStride, rawSize, width, height);
        if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_NV12) {
            convertNvToYuv420(rawScratch, rawSize, effectiveStride, width, height, false, dstPlanar, outYuv);
        } else if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_NV21) {
            convertNvToYuv420(rawScratch, rawSize, effectiveStride, width, height, true, dstPlanar, outYuv);
        } else if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_YV12) {
            convertYv12ToYuv420(rawScratch, rawSize, effectiveStride, width, height, dstPlanar, outYuv);
        } else if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_YUYV) {
            convertYuyvToYuv420(rawScratch, rawSize, effectiveStride, width, height, dstPlanar, outYuv);
        } else if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGB3
                || srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGB4
                || srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGBP
                || srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGBR) {
            convertRgbToYuv420(rawScratch, rawSize, effectiveStride, width, height, srcFourcc, dstPlanar, outYuv);
        } else {
            throw new ConfigurationException("Unsupported V4L2 raw format: 0x" + Integer.toHexString(srcFourcc) + " ("
                    + fourccToString(srcFourcc) + ")");
        }

        return dstSize;
    }

    private static int resolveInputStride(int srcFourcc, int advertisedStride, int rawSize, int width, int height) {
        int safeHeight = Math.max(height, 1);
        switch (srcFourcc) {
            case AmlogicV4l2CaptureNative.PIXEL_FORMAT_NV12:
            case AmlogicV4l2CaptureNative.PIXEL_FORMAT_NV21: {
                int stride = Math.max(advertisedStride, width);
                long expected = (long) stride * safeHeight * 3L / 2L;
                if (expected <= rawSize) {
                    return stride;
                }

                int inferredYSize = rawSize * 2 / 3;
                if (inferredYSize > 0 && inferredYSize % safeHeight == 0) {
                    int inferredStride = inferredYSize / safeHeight;
                    if (inferredStride >= width) {
                        long inferredExpected = (long) inferredStride * safeHeight * 3L / 2L;
                        if (inferredExpected <= rawSize) {
                            return inferredStride;
                        }
                    }
                }

                return width;
            }
            case AmlogicV4l2CaptureNative.PIXEL_FORMAT_YUYV: {
                int minStride = width * 2;
                int stride = Math.max(advertisedStride, minStride);
                long expected = (long) stride * safeHeight;
                if (expected <= rawSize) {
                    return stride;
                }

                if (rawSize % safeHeight == 0) {
                    int inferredStride = rawSize / safeHeight;
                    if (inferredStride >= minStride) {
                        return inferredStride;
                    }
                }
                return minStride;
            }
            case AmlogicV4l2CaptureNative.PIXEL_FORMAT_YV12: {
                int stride = Math.max(advertisedStride, width);
                int chromaStride = Math.max(stride / 2, width / 2);
                long expected = (long) stride * safeHeight + (long) chromaStride * (safeHeight / 2) * 2L;
                if (expected <= rawSize) {
                    return stride;
                }

                int inferredYSize = rawSize * 2 / 3;
                if (inferredYSize > 0 && inferredYSize % safeHeight == 0) {
                    int inferredStride = inferredYSize / safeHeight;
                    if (inferredStride >= width) {
                        return inferredStride;
                    }
                }
                return width;
            }
            case AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGB3: {
                int minStride = width * 3;
                int stride = Math.max(advertisedStride, minStride);
                long expected = (long) stride * safeHeight;
                if (expected <= rawSize) {
                    return stride;
                }
                if (rawSize % safeHeight == 0) {
                    int inferredStride = rawSize / safeHeight;
                    if (inferredStride >= minStride) {
                        return inferredStride;
                    }
                }
                return minStride;
            }
            case AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGB4: {
                int minStride = width * 4;
                int stride = Math.max(advertisedStride, minStride);
                long expected = (long) stride * safeHeight;
                if (expected <= rawSize) {
                    return stride;
                }
                if (rawSize % safeHeight == 0) {
                    int inferredStride = rawSize / safeHeight;
                    if (inferredStride >= minStride) {
                        return inferredStride;
                    }
                }
                return minStride;
            }
            case AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGBP:
            case AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGBR: {
                int minStride = width * 2;
                int stride = Math.max(advertisedStride, minStride);
                long expected = (long) stride * safeHeight;
                if (expected <= rawSize) {
                    return stride;
                }
                if (rawSize % safeHeight == 0) {
                    int inferredStride = rawSize / safeHeight;
                    if (inferredStride >= minStride) {
                        return inferredStride;
                    }
                }
                return minStride;
            }
            default:
                return Math.max(advertisedStride, width);
        }
    }

    private static void convertNvToYuv420(byte[] src, int srcSize, int srcStride, int width, int height, boolean srcNv21, boolean dstPlanar,
            byte[] dst) throws ConfigurationException {
        int yPlaneSize = srcStride * height;
        int uvPlaneSize = srcStride * (height / 2);
        if (srcSize < yPlaneSize + uvPlaneSize) {
            throw new ConfigurationException("NV frame too small: " + srcSize);
        }

        int frameSize = width * height;
        int halfWidth = width / 2;
        int halfHeight = height / 2;

        // Y
        for (int y = 0; y < height; ++y) {
            System.arraycopy(src, y * srcStride, dst, y * width, width);
        }

        if (dstPlanar) {
            int uOffset = frameSize;
            int vOffset = frameSize + frameSize / 4;
            int srcUvBase = yPlaneSize;
            for (int row = 0; row < halfHeight; ++row) {
                int srcRow = srcUvBase + row * srcStride;
                int dstRow = row * halfWidth;
                for (int col = 0; col < halfWidth; ++col) {
                    int chroma = srcRow + col * 2;
                    byte u = srcNv21 ? src[chroma + 1] : src[chroma];
                    byte v = srcNv21 ? src[chroma] : src[chroma + 1];
                    dst[uOffset + dstRow + col] = u;
                    dst[vOffset + dstRow + col] = v;
                }
            }
        } else {
            int dstUvOffset = frameSize;
            int srcUvBase = yPlaneSize;
            for (int row = 0; row < halfHeight; ++row) {
                int srcRow = srcUvBase + row * srcStride;
                int dstRow = dstUvOffset + row * width;
                if (!srcNv21) {
                    System.arraycopy(src, srcRow, dst, dstRow, width);
                } else {
                    for (int col = 0; col < width; col += 2) {
                        dst[dstRow + col] = src[srcRow + col + 1];
                        dst[dstRow + col + 1] = src[srcRow + col];
                    }
                }
            }
        }
    }

    private static void convertYuyvToYuv420(byte[] src, int srcSize, int srcStride, int width, int height, boolean dstPlanar, byte[] dst)
            throws ConfigurationException {
        int minRow = width * 2;
        if (srcStride < minRow || srcSize < srcStride * height) {
            throw new ConfigurationException("YUYV frame too small/invalid stride");
        }

        int frameSize = width * height;
        int halfWidth = width / 2;
        int uOffset = frameSize;
        int vOffset = frameSize + frameSize / 4;
        int uvOffset = frameSize;

        for (int y = 0; y < height; ++y) {
            int srcRow = y * srcStride;
            int yRow = y * width;
            int uvRow = (y / 2) * width;
            int uvPlanarRow = (y / 2) * halfWidth;
            for (int x = 0; x < halfWidth; ++x) {
                int srcIndex = srcRow + x * 4;
                byte y0 = src[srcIndex];
                byte u = src[srcIndex + 1];
                byte y1 = src[srcIndex + 2];
                byte v = src[srcIndex + 3];

                dst[yRow + x * 2] = y0;
                dst[yRow + x * 2 + 1] = y1;

                if ((y & 1) == 0) {
                    if (dstPlanar) {
                        dst[uOffset + uvPlanarRow + x] = u;
                        dst[vOffset + uvPlanarRow + x] = v;
                    } else {
                        int uvIndex = uvOffset + uvRow + x * 2;
                        dst[uvIndex] = u;
                        dst[uvIndex + 1] = v;
                    }
                }
            }
        }
    }

    private static void convertYv12ToYuv420(byte[] src, int srcSize, int srcStride, int width, int height, boolean dstPlanar, byte[] dst)
            throws ConfigurationException {
        int frameSize = width * height;
        int halfWidth = width / 2;
        int halfHeight = height / 2;
        int chromaStride = Math.max(srcStride / 2, halfWidth);
        int yPlaneSize = srcStride * height;
        int chromaPlaneSize = chromaStride * halfHeight;
        if (srcSize < yPlaneSize + chromaPlaneSize * 2) {
            throw new ConfigurationException("YV12 frame too small: " + srcSize);
        }

        int srcVBase = yPlaneSize;
        int srcUBase = yPlaneSize + chromaPlaneSize;

        for (int y = 0; y < height; ++y) {
            System.arraycopy(src, y * srcStride, dst, y * width, width);
        }

        if (dstPlanar) {
            int uOffset = frameSize;
            int vOffset = frameSize + frameSize / 4;
            for (int row = 0; row < halfHeight; ++row) {
                int srcRowOffset = row * chromaStride;
                int dstRowOffset = row * halfWidth;
                System.arraycopy(src, srcUBase + srcRowOffset, dst, uOffset + dstRowOffset, halfWidth);
                System.arraycopy(src, srcVBase + srcRowOffset, dst, vOffset + dstRowOffset, halfWidth);
            }
            return;
        }

        int uvOffset = frameSize;
        for (int row = 0; row < halfHeight; ++row) {
            int srcRowOffset = row * chromaStride;
            int dstRowOffset = uvOffset + row * width;
            for (int col = 0; col < halfWidth; ++col) {
                dst[dstRowOffset + col * 2] = src[srcUBase + srcRowOffset + col];
                dst[dstRowOffset + col * 2 + 1] = src[srcVBase + srcRowOffset + col];
            }
        }
    }

    private static void convertRgbToYuv420(byte[] src, int srcSize, int srcStride, int width, int height, int srcFourcc, boolean dstPlanar,
            byte[] dst) throws ConfigurationException {
        int bytesPerPixel = bytesPerPixel(srcFourcc);
        int minStride = width * bytesPerPixel;
        if (srcStride < minStride || srcSize < srcStride * height) {
            throw new ConfigurationException("RGB frame too small/invalid stride");
        }

        int frameSize = width * height;
        int halfWidth = width / 2;
        int uOffset = frameSize;
        int vOffset = frameSize + frameSize / 4;
        int uvOffset = frameSize;
        int[] rgb = new int[3];

        for (int y = 0; y < height; ++y) {
            int srcRow = y * srcStride;
            int yRow = y * width;
            int uvRow = (y / 2) * width;
            int uvPlanarRow = (y / 2) * halfWidth;

            for (int x = 0; x < halfWidth; ++x) {
                int base = srcRow + x * bytesPerPixel * 2;

                readRgb(src, base, srcFourcc, rgb);
                int y0 = rgbToY(rgb[0], rgb[1], rgb[2]);
                int u0 = rgbToU(rgb[0], rgb[1], rgb[2]);
                int v0 = rgbToV(rgb[0], rgb[1], rgb[2]);

                readRgb(src, base + bytesPerPixel, srcFourcc, rgb);
                int y1 = rgbToY(rgb[0], rgb[1], rgb[2]);
                int u1 = rgbToU(rgb[0], rgb[1], rgb[2]);
                int v1 = rgbToV(rgb[0], rgb[1], rgb[2]);

                dst[yRow + x * 2] = (byte) y0;
                dst[yRow + x * 2 + 1] = (byte) y1;

                if ((y & 1) == 0) {
                    int nextRow = Math.min(y + 1, height - 1) * srcStride;
                    int nextBase = nextRow + x * bytesPerPixel * 2;

                    readRgb(src, nextBase, srcFourcc, rgb);
                    int u2 = rgbToU(rgb[0], rgb[1], rgb[2]);
                    int v2 = rgbToV(rgb[0], rgb[1], rgb[2]);

                    readRgb(src, nextBase + bytesPerPixel, srcFourcc, rgb);
                    int u3 = rgbToU(rgb[0], rgb[1], rgb[2]);
                    int v3 = rgbToV(rgb[0], rgb[1], rgb[2]);

                    int u = clamp((u0 + u1 + u2 + u3 + 2) / 4);
                    int v = clamp((v0 + v1 + v2 + v3 + 2) / 4);
                    if (dstPlanar) {
                        dst[uOffset + uvPlanarRow + x] = (byte) u;
                        dst[vOffset + uvPlanarRow + x] = (byte) v;
                    } else {
                        int uvIndex = uvOffset + uvRow + x * 2;
                        dst[uvIndex] = (byte) u;
                        dst[uvIndex + 1] = (byte) v;
                    }
                }
            }
        }
    }

    private static int bytesPerPixel(int srcFourcc) throws ConfigurationException {
        if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGB3) {
            return 3;
        }
        if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGB4) {
            return 4;
        }
        if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGBP || srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGBR) {
            return 2;
        }
        throw new ConfigurationException("Unsupported RGB format: 0x" + Integer.toHexString(srcFourcc));
    }

    private static void readRgb(byte[] src, int offset, int srcFourcc, int[] rgb) throws ConfigurationException {
        if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGB3) {
            rgb[0] = src[offset] & 0xFF;
            rgb[1] = src[offset + 1] & 0xFF;
            rgb[2] = src[offset + 2] & 0xFF;
            return;
        }
        if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGB4) {
            rgb[0] = src[offset] & 0xFF;
            rgb[1] = src[offset + 1] & 0xFF;
            rgb[2] = src[offset + 2] & 0xFF;
            return;
        }
        int packed = (src[offset] & 0xFF) | ((src[offset + 1] & 0xFF) << 8);
        if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGBP) {
            int r = (packed >> 11) & 0x1F;
            int g = (packed >> 5) & 0x3F;
            int b = packed & 0x1F;
            rgb[0] = (r * 255 + 15) / 31;
            rgb[1] = (g * 255 + 31) / 63;
            rgb[2] = (b * 255 + 15) / 31;
            return;
        }
        if (srcFourcc == AmlogicV4l2CaptureNative.PIXEL_FORMAT_RGBR) {
            int r = (packed >> 10) & 0x1F;
            int g = (packed >> 5) & 0x1F;
            int b = packed & 0x1F;
            rgb[0] = (r * 255 + 15) / 31;
            rgb[1] = (g * 255 + 15) / 31;
            rgb[2] = (b * 255 + 15) / 31;
            return;
        }
        throw new ConfigurationException("Unsupported RGB format: 0x" + Integer.toHexString(srcFourcc));
    }

    private static int rgbToY(int r, int g, int b) {
        return clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
    }

    private static int rgbToU(int r, int g, int b) {
        return clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
    }

    private static int rgbToV(int r, int g, int b) {
        return clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
    }

    private static int clamp(int v) {
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return v;
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            try {
                streamCapture();
            } catch (ConfigurationException e) {
                // Do not print stack trace, a user-friendly message has already been logged.
            } catch (IOException e) {
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Amlogic V4L2 video error", e);
                }
            } finally {
                Ln.d("Amlogic V4L2 streaming stopped");
                listener.onTerminated(true);
            }
        }, "video-amlogic-v4l2-capture");
        thread.start();
    }

    @Override
    public void stop() {
        stopped.set(true);
        resetRequested.set(true);
    }

    public void requestReset() {
        resetRequested.set(true);
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

    private static byte[] toArray(ByteBuffer buffer) {
        ByteBuffer dup = buffer.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static byte[] extractAvcConfig(byte[] frame) {
        if (frame == null || frame.length < 4) {
            return null;
        }

        byte[] sps = null;
        byte[] pps = null;

        int searchFrom = 0;
        while (true) {
            int start = findAnnexBStartCode(frame, searchFrom);
            if (start < 0) {
                break;
            }
            int startCodeLength = getStartCodeLength(frame, start);
            int nalStart = start + startCodeLength;
            int next = findAnnexBStartCode(frame, nalStart);
            int nalEnd = next >= 0 ? next : frame.length;
            if (nalStart < nalEnd) {
                int nalType = frame[nalStart] & 0x1F;
                if (nalType == 7) {
                    sps = Arrays.copyOfRange(frame, start, nalEnd);
                } else if (nalType == 8) {
                    pps = Arrays.copyOfRange(frame, start, nalEnd);
                }
            }
            searchFrom = nalEnd;
        }

        if (sps == null || pps == null) {
            return null;
        }

        byte[] config = new byte[sps.length + pps.length];
        System.arraycopy(sps, 0, config, 0, sps.length);
        System.arraycopy(pps, 0, config, sps.length, pps.length);
        return config;
    }

    private static boolean containsAvcNalType(byte[] frame, int targetNalType) {
        if (frame == null || frame.length < 4) {
            return false;
        }

        int searchFrom = 0;
        while (true) {
            int start = findAnnexBStartCode(frame, searchFrom);
            if (start < 0) {
                return false;
            }
            int startCodeLength = getStartCodeLength(frame, start);
            int nalStart = start + startCodeLength;
            int next = findAnnexBStartCode(frame, nalStart);
            int nalEnd = next >= 0 ? next : frame.length;
            if (nalStart < nalEnd) {
                int nalType = frame[nalStart] & 0x1F;
                if (nalType == targetNalType) {
                    return true;
                }
            }
            searchFrom = nalEnd;
        }
    }

    private static int findAnnexBStartCode(byte[] frame, int from) {
        int len = frame.length;
        for (int i = Math.max(0, from); i <= len - 3; ++i) {
            int startCodeLength = getStartCodeLength(frame, i);
            if (startCodeLength != 0) {
                return i;
            }
        }
        return -1;
    }

    private static int getStartCodeLength(byte[] frame, int index) {
        if (index + 2 < frame.length && frame[index] == 0 && frame[index + 1] == 0 && frame[index + 2] == 1) {
            return 3;
        }
        if (index + 3 < frame.length && frame[index] == 0 && frame[index + 1] == 0 && frame[index + 2] == 0 && frame[index + 3] == 1) {
            return 4;
        }
        return 0;
    }

    private static String fourccToString(int fourcc) {
        char a = (char) (fourcc & 0xFF);
        char b = (char) ((fourcc >> 8) & 0xFF);
        char c = (char) ((fourcc >> 16) & 0xFF);
        char d = (char) ((fourcc >> 24) & 0xFF);
        return new String(new char[]{a, b, c, d});
    }

    private static boolean isBrokenPipe(IOException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("EPIPE") || message.contains("Broken pipe");
    }

    private static List<String> resolveDevicePaths(Options options) throws ConfigurationException {
        boolean allowFallbackDevice = options.getAmlogicV4l2FallbackDevice();
        int preferredInstance = options.getAmlogicV4l2Instance();
        int fallbackInstance = preferredInstance == 0 ? 1 : 0;
        String preferredPath = resolveDevicePath(preferredInstance);
        String fallbackPath = resolveDevicePath(fallbackInstance);

        List<String> candidates = new ArrayList<>(allowFallbackDevice ? 3 : 2);
        if (options.getAmlogicV4l2DeviceSet()) {
            String explicitPath = options.getAmlogicV4l2Device();
            if (explicitPath != null) {
                explicitPath = explicitPath.trim();
            }
            if (explicitPath != null && !explicitPath.isEmpty()) {
                candidates.add(explicitPath);
                if (!allowFallbackDevice) {
                    return candidates;
                }
            }
        }
        if (!candidates.contains(preferredPath)) {
            candidates.add(preferredPath);
        }
        if (allowFallbackDevice && !candidates.contains(fallbackPath)) {
            candidates.add(fallbackPath);
        }
        return candidates;
    }

    private static String resolveDevicePath(int instanceId) throws ConfigurationException {
        if (instanceId == 0) {
            return "/dev/video11";
        }
        if (instanceId == 1) {
            return "/dev/video12";
        }
        throw new ConfigurationException("Amlogic instance id must be 0 or 1, got: " + instanceId);
    }
}
