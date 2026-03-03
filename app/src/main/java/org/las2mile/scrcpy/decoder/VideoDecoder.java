package org.las2mile.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoDecoder {
    public interface VideoSizeListener {
        void onVideoSizeChanged(int width, int height);
    }

    private MediaCodec mCodec;
    private Worker mWorker;
    private final AtomicBoolean mIsConfigured = new AtomicBoolean(false);
    private volatile VideoSizeListener mVideoSizeListener;

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        if (mWorker != null) {
            mWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void configure(Surface surface, int width, int height, String mimeType, ByteBuffer csd0, ByteBuffer csd1) {
        if (mWorker != null) {
            mWorker.configure(surface, width, height, mimeType, csd0, csd1);
        }
    }

    public void setVideoSizeListener(VideoSizeListener listener) {
        mVideoSizeListener = listener;
    }

    public boolean isConfigured() {
        return mIsConfigured.get();
    }


    public void start() {
        if (mWorker == null || !mWorker.isAlive()) {
            mWorker = new Worker();
            mWorker.setRunning(true);
            mWorker.start();
        } else {
            mWorker.setRunning(true);
        }
    }

    public void stop() {
        if (mWorker != null) {
            mWorker.setRunning(false);
            mWorker.interrupt();
            try {
                mWorker.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mWorker = null;
            mIsConfigured.set(false);
            releaseCodec();
        }
    }

    private synchronized void releaseCodec() {
        if (mCodec == null) {
            return;
        }
        try {
            mCodec.stop();
        } catch (IllegalStateException ignore) {
            // ignore
        }
        try {
            mCodec.release();
        } catch (IllegalStateException ignore) {
            // ignore
        }
        mCodec = null;
    }

    private class Worker extends Thread {

        private AtomicBoolean mIsRunning = new AtomicBoolean(false);

        Worker() {
        }

        private void setRunning(boolean isRunning) {
            mIsRunning.set(isRunning);
        }

        private void configure(Surface surface, int width, int height, String mimeType, ByteBuffer csd0, ByteBuffer csd1) {
            if (mIsConfigured.get()) {
                mIsConfigured.set(false);
                releaseCodec();
            }
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
            if (csd0 != null) {
                format.setByteBuffer("csd-0", csd0);
            }
            if (csd1 != null) {
                format.setByteBuffer("csd-1", csd1);
            }
            try {
                mCodec = MediaCodec.createDecoderByType(mimeType);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create codec", e);
            }
            try {
                mCodec.configure(format, surface, null, 0);
                mCodec.start();
                mIsConfigured.set(true);
            } catch (IllegalStateException e) {
                mIsConfigured.set(false);
                releaseCodec();
            }
        }


        @SuppressWarnings("deprecation")
        public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            if (mIsConfigured.get() && mIsRunning.get()) {
                try {
                    int index = mCodec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer;

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                            buffer = mCodec.getInputBuffers()[index];
                            buffer.clear();
                        } else {
                            buffer = mCodec.getInputBuffer(index);
                        }
                        if (buffer != null) {
                            buffer.put(data, offset, size);
                            mCodec.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
                        }
                    }
                } catch (IllegalStateException e) {
                    Log.w("scrcpy", "VideoDecoder input path failed, resetting codec", e);
                    mIsConfigured.set(false);
                    releaseCodec();
                }
            }
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (mIsRunning.get()) {
                if (mIsConfigured.get()) {
                    try {
                        int index = mCodec.dequeueOutputBuffer(info, 0);
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat outputFormat = mCodec.getOutputFormat();
                            int width = outputFormat.containsKey(MediaFormat.KEY_WIDTH) ? outputFormat.getInteger(MediaFormat.KEY_WIDTH) : 0;
                            int height = outputFormat.containsKey(MediaFormat.KEY_HEIGHT) ? outputFormat.getInteger(MediaFormat.KEY_HEIGHT) : 0;
                            VideoSizeListener listener = mVideoSizeListener;
                            if (listener != null && width > 0 && height > 0) {
                                listener.onVideoSizeChanged(width, height);
                            }
                        } else if (index >= 0) {
                            // setting true is telling system to render frame onto Surface
                            mCodec.releaseOutputBuffer(index, true);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                break;
                            }
                        }
                    } catch (IllegalStateException e) {
                        // Surface/codecs may be transiently invalid during orientation/insets changes.
                        Log.w("scrcpy", "VideoDecoder output path failed, resetting codec", e);
                        mIsConfigured.set(false);
                        releaseCodec();
                    }
                } else {
                    // just waiting to be configured, then decode and render
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

        }
    }
}
