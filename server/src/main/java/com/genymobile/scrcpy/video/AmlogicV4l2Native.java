package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.Server;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Ln;

import android.os.Build;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AmlogicV4l2Native implements Closeable {

    public static final long FLAG_KEY_FRAME = 1L;
    public static final int PIXEL_FORMAT_H264 = 875967048;
    public static final int PIXEL_FORMAT_NV12 = 842094158;
    public static final int PIXEL_FORMAT_NV21 = 825382478;
    public static final int PIXEL_FORMAT_YUYV = 1448695129;
    public static final int PIXEL_FORMAT_YV12 = 842094169;
    public static final int PIXEL_FORMAT_RGB3 = 859981650;
    public static final int PIXEL_FORMAT_RGB4 = 876758866;
    public static final int PIXEL_FORMAT_RGBP = 1346520914;
    public static final int PIXEL_FORMAT_RGBR = 1380075346;
    public static final int EAGAIN = -11;

    private static final String LIB_NAME = "scrcpy_v4l2";
    private static final String LIB_FILE = "lib" + LIB_NAME + ".so";
    private static volatile boolean libraryLoaded;

    private final long[] frameInfo = new long[3];
    private long handle;
    private final Size size;
    private final int frameCapacity;
    private final int pixelFormat;
    private final int bytesPerLine;

    public static final class FrameMetadata {
        public long ptsUs;
        public boolean keyFrame;
        public int bufferIndex = -1;
    }

    private AmlogicV4l2Native(long handle, Size size, int frameCapacity, int pixelFormat, int bytesPerLine) {
        this.handle = handle;
        this.size = size;
        this.frameCapacity = frameCapacity;
        this.pixelFormat = pixelFormat;
        this.bytesPerLine = bytesPerLine;
    }

    public static void preload() throws IOException {
        ensureLibraryLoaded();
    }

    public static AmlogicV4l2Native open(String devicePath, int width, int height, int fps, int portType, int sourceType,
            int mode, int rotation, int cropLeft, int cropTop, int cropWidth, int cropHeight, int reqBufCount,
            int preferredPixelFormat) throws IOException {
        ensureLibraryLoaded();
        long[] openInfo = new long[5];
        long handle = nativeOpen(devicePath, width, height, fps, portType, sourceType, mode, rotation, cropLeft, cropTop, cropWidth,
                cropHeight, reqBufCount, preferredPixelFormat, openInfo);
        if (handle == 0) {
            throw new IOException("Failed to initialize V4L2 capture");
        }
        Size size = new Size((int) openInfo[0], (int) openInfo[1]);
        int capacity = (int) openInfo[2];
        int pixelFormat = (int) openInfo[3];
        int bytesPerLine = (int) openInfo[4];
        return new AmlogicV4l2Native(handle, size, capacity, pixelFormat, bytesPerLine);
    }

    public Size getSize() {
        return size;
    }

    public int getFrameCapacity() {
        return frameCapacity;
    }

    public int getPixelFormat() {
        return pixelFormat;
    }

    public int getBytesPerLine() {
        return bytesPerLine;
    }

    public int readFrame(ByteBuffer buffer, FrameMetadata metadata) throws IOException {
        if (handle == 0) {
            throw new IOException("V4L2 capture is already closed");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        int size = nativeReadFrame(handle, buffer, frameInfo);
        if (size > 0) {
            metadata.ptsUs = frameInfo[0];
            metadata.keyFrame = (frameInfo[1] & FLAG_KEY_FRAME) != 0;
            metadata.bufferIndex = (int) frameInfo[2];
        } else {
            metadata.bufferIndex = -1;
        }
        return size;
    }

    public void releaseFrame(FrameMetadata metadata) throws IOException {
        if (metadata != null && metadata.bufferIndex >= 0) {
            nativeReleaseFrame(handle, metadata.bufferIndex);
            metadata.bufferIndex = -1;
        }
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
    }

    private static synchronized void ensureLibraryLoaded() throws IOException {
        if (libraryLoaded) {
            return;
        }

        try {
            System.loadLibrary(LIB_NAME);
            libraryLoaded = true;
            Ln.i("Loaded native library with System.loadLibrary: " + LIB_FILE);
            return;
        } catch (UnsatisfiedLinkError e) {
            Ln.w("System.loadLibrary failed, fallback to extraction: " + e.getMessage());
        }

        String extractedPath = extractBundledLibrary();
        System.load(extractedPath);
        libraryLoaded = true;
        Ln.i("Loaded native library from extracted path: " + extractedPath);
    }

    private static String extractBundledLibrary() throws IOException {
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        if (supportedAbis == null || supportedAbis.length == 0) {
            throw new IOException("No supported ABI reported by runtime");
        }

        try (ZipFile zipFile = new ZipFile(Server.SERVER_PATH)) {
            for (String abi : supportedAbis) {
                String entryName = "lib/" + abi + "/" + LIB_FILE;
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) {
                    continue;
                }

                File outFile = new File("/data/local/tmp", "libscrcpy_v4l2-" + abi + ".so");
                try (InputStream in = zipFile.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(outFile, false)) {
                    byte[] buffer = new byte[16384];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                    out.getFD().sync();
                }

                outFile.setReadable(true, false);
                outFile.setExecutable(true, false);
                outFile.setWritable(true, true);
                return outFile.getAbsolutePath();
            }
        }

        throw new IOException("Native library not found in server package for ABIs: " + joinAbis(supportedAbis));
    }

    private static String joinAbis(String[] abis) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < abis.length; ++i) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(abis[i]);
        }
        return builder.toString();
    }

    private static native long nativeOpen(String devicePath, int width, int height, int fps, int portType, int sourceType,
            int mode, int rotation, int cropLeft, int cropTop, int cropWidth, int cropHeight, int reqBufCount,
            int preferredPixelFormat, long[] openInfo) throws IOException;

    private static native int nativeReadFrame(long handle, ByteBuffer frameBuffer, long[] frameInfo) throws IOException;

    private static native void nativeReleaseFrame(long handle, int bufferIndex) throws IOException;

    private static native void nativeClose(long handle);
}
