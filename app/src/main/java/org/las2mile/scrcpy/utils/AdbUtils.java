package org.las2mile.scrcpy.utils;

import android.content.Context;
import android.util.Base64;

import com.android.adblib.AdbBase64;
import com.android.adblib.AdbConnection;
import com.android.adblib.AdbCrypto;
import com.android.adblib.AdbStream;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.UUID;

public final class AdbUtils {

    private static final int SYNC_DATA_MAX = 64 * 1024;
    private static final int DEFAULT_REMOTE_FILE_MODE = 0644;

    private AdbUtils() {
        // no instances
    }

    public static AdbBase64 getBase64Impl() {
        return new AdbBase64() {
            @Override
            public String encodeToString(byte[] data) {
                return Base64.encodeToString(data, Base64.NO_WRAP);
            }
        };
    }

    public static AdbCrypto setupCrypto(Context context) throws IOException {
        AdbCrypto c;
        try {
            c = AdbCrypto.loadAdbKeyPair(getBase64Impl(), context.getFileStreamPath("priv.key"), context.getFileStreamPath("pub.key"));
        } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException | NullPointerException e) {
            c = null;
        }

        if (c == null) {
            try {
                c = AdbCrypto.generateAdbKeyPair(getBase64Impl());
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("Failed to generate adb key pair", e);
            }
            c.saveAdbKeyPair(context.getFileStreamPath("priv.key"), context.getFileStreamPath("pub.key"));
        }

        return c;
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignore) {
        }
    }

    public static void closeQuietly(Socket sock) {
        if (sock == null) {
            return;
        }
        try {
            sock.close();
        } catch (IOException ignore) {
        }
    }

    public static class AdbSession implements Closeable {
        public final Socket socket;
        public final AdbConnection adb;

        public AdbSession(Socket socket, AdbConnection adb) {
            this.socket = socket;
            this.adb = adb;
        }

        @Override
        public void close() {
            closeQuietly(adb);
            closeQuietly(socket);
        }
    }

    public static AdbSession connect(Context context, String ip, int port, int connectTimeoutMs, int socketTimeoutMs) throws IOException, InterruptedException {
        AdbCrypto crypto = setupCrypto(context);
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(ip, port), connectTimeoutMs);
            sock.setSoTimeout(socketTimeoutMs);
        } catch (UnknownHostException e) {
            throw new UnknownHostException(ip + " is not a valid host");
        } catch (ConnectException e) {
            throw new ConnectException("Device at " + ip + ":" + port + " refused connection");
        }

        AdbConnection adb = null;
        try {
            adb = AdbConnection.create(sock, crypto);
            adb.connect();
            return new AdbSession(sock, adb);
        } catch (IOException | InterruptedException | IllegalStateException e) {
            closeQuietly(sock);
            throw e;
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Cancelled");
        }
    }

    /**
     * Wait for a specific marker string in the output stream.
     */
    private static boolean waitForMarker(AdbStream stream, String marker, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder tail = new StringBuilder();
        int markerLen = marker.length();

        while (System.currentTimeMillis() < deadline) {
            checkCancelled();
            byte[] responseBytes;
            try {
                responseBytes = stream.read();
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (responseBytes == null || responseBytes.length == 0) {
                continue;
            }
            String response = new String(responseBytes, StandardCharsets.UTF_8);
            tail.append(response);
            
            if (tail.indexOf(marker) >= 0) {
                return true;
            }
            
            // Keep tail small enough to save memory, but large enough to not clip the marker
            if (tail.length() > markerLen * 2) {
                tail.delete(0, tail.length() - markerLen * 2);
            }
        }
        return false;
    }

    /**
     * Executes a command on the remote shell without returning the output, blocking until it finishes.
     */
    public static void executeShellCommandWait(AdbConnection adb, String command, long timeoutMs) throws IOException, InterruptedException {
        AdbStream stream = null;
        try {
            synchronized (adb) {
                stream = adb.open("shell:");
            }
            String marker = "DONE_" + UUID.randomUUID().toString().replace("-", "");
            stream.write((command + " \n").getBytes(StandardCharsets.UTF_8));
            stream.write(("echo " + marker + " \n").getBytes(StandardCharsets.UTF_8));
            
            if (!waitForMarker(stream, marker, timeoutMs)) {
                throw new SocketTimeoutException("Timeout waiting for command completion: " + command);
            }
        } finally {
            closeQuietly(stream);
        }
    }

    /**
     * Executes a detached shell command (e.g. background server) and closes the shell channel.
     */
    public static void executeDetachedShellCommand(AdbConnection adb, String command) throws IOException, InterruptedException {
        AdbStream stream = null;
        try {
            synchronized (adb) {
                stream = adb.open("shell:");
            }
            String trimmed = command == null ? "" : command.trim();
            if (trimmed.endsWith(";")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.isEmpty()) {
                return;
            }

                        String escaped = trimmed.replace("'", "'\\''");
                        String detachedCommand = "("
                                + "(command -v nohup >/dev/null 2>&1 && nohup sh -c '" + escaped + "')"
                                + " || "
                                + "(command -v setsid >/dev/null 2>&1 && setsid sh -c '" + escaped + "')"
                                + " || "
                                + "(" + trimmed + ")"
                                + ") >/dev/null 2>&1 < /dev/null &";
            
                        stream.write((detachedCommand + "\n").getBytes(StandardCharsets.UTF_8));
                        // Instead of Sleep, just write an exit command to ensure the shell processes the buffer before channel closes
                        stream.write("exit\n".getBytes(StandardCharsets.UTF_8));            
            // Read until the stream is naturally closed by 'exit'
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    byte[] b = stream.read();
                    if (b == null) break;
                } catch (SocketTimeoutException ignored) {
                    break;
                }
            }
        } finally {
            closeQuietly(stream);
        }
    }

    /**
     * Pushes a file to the remote device through the ADB sync protocol.
     */
    public static void pushFile(AdbConnection adb, byte[] fileBytes, String remotePath, long timeoutMs) throws IOException, InterruptedException {
        if (adb == null) {
            throw new IOException("adb is null");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IOException("fileBytes is empty");
        }
        String normalizedPath = remotePath == null ? "" : remotePath.trim();
        if (normalizedPath.isEmpty()) {
            throw new IOException("remotePath is empty");
        }

        AdbStream sync = null;
        long deadlineMs = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        try {
            synchronized (adb) {
                sync = adb.open("sync:");
            }

            int maxAdbPayload = adb.getMaxData();
            int maxSyncDataPerPacket = Math.min(SYNC_DATA_MAX, Math.max(1, maxAdbPayload - 8));

            byte[] sendPath = (normalizedPath + "," + DEFAULT_REMOTE_FILE_MODE).getBytes(StandardCharsets.UTF_8);
            if (8 + sendPath.length > maxAdbPayload) {
                throw new IOException("remotePath is too long for adb stream max payload: " + normalizedPath);
            }
            writeSyncRequest(sync, "SEND", sendPath);

            int offset = 0;
            while (offset < fileBytes.length) {
                checkCancelled();
                checkSyncTimeout(deadlineMs, "Timeout while sending sync DATA packet");

                int chunkSize = Math.min(maxSyncDataPerPacket, fileBytes.length - offset);
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(fileBytes, offset, chunk, 0, chunkSize);
                writeSyncRequest(sync, "DATA", chunk);
                offset += chunkSize;
            }

            checkSyncTimeout(deadlineMs, "Timeout while finalizing sync transfer");
            int mtimeSeconds = (int) (System.currentTimeMillis() / 1000L);
            writeSyncDone(sync, mtimeSeconds);

            SyncStreamReader reader = new SyncStreamReader(sync);
            byte[] header = reader.readFully(8);
            String responseId = new String(header, 0, 4, StandardCharsets.US_ASCII);
            int responseLength = decodeLittleEndianInt(header, 4);

            if ("OKAY".equals(responseId)) {
                if (responseLength > 0) {
                    reader.readFully(responseLength);
                }
                return;
            }

            if ("FAIL".equals(responseId)) {
                byte[] errorBytes = responseLength > 0 ? reader.readFully(responseLength) : new byte[0];
                String errorMessage = new String(errorBytes, StandardCharsets.UTF_8);
                throw new IOException("sync push failed for " + normalizedPath + ": " + errorMessage);
            }

            throw new IOException("Unexpected sync response: " + responseId + " (len=" + responseLength + ")");
        } finally {
            closeQuietly(sync);
        }
    }

    private static void writeSyncRequest(AdbStream stream, String id, byte[] payload) throws IOException, InterruptedException {
        byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
        byte[] body = payload == null ? new byte[0] : payload;
        byte[] packet = new byte[8 + body.length];
        if (idBytes.length != 4) {
            throw new IOException("Invalid sync id: " + id);
        }
        System.arraycopy(idBytes, 0, packet, 0, 4);
        encodeLittleEndianInt(packet, 4, body.length);
        if (body.length > 0) {
            System.arraycopy(body, 0, packet, 8, body.length);
        }
        stream.write(packet);
    }

    private static void writeSyncDone(AdbStream stream, int mtimeSeconds) throws IOException, InterruptedException {
        byte[] done = new byte[8];
        done[0] = 'D';
        done[1] = 'O';
        done[2] = 'N';
        done[3] = 'E';
        encodeLittleEndianInt(done, 4, mtimeSeconds);
        stream.write(done);
    }

    private static void checkSyncTimeout(long deadlineMs, String message) throws SocketTimeoutException {
        if (deadlineMs == Long.MAX_VALUE) {
            return;
        }
        if (System.currentTimeMillis() > deadlineMs) {
            throw new SocketTimeoutException(message);
        }
    }

    private static void encodeLittleEndianInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static int decodeLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static final class SyncStreamReader {
        private final AdbStream stream;
        private byte[] chunk = new byte[0];
        private int offset;

        private SyncStreamReader(AdbStream stream) {
            this.stream = stream;
        }

        private byte[] readFully(int size) throws IOException, InterruptedException {
            if (size < 0) {
                throw new IOException("Negative sync read size: " + size);
            }
            byte[] out = new byte[size];
            int outOffset = 0;
            while (outOffset < size) {
                if (offset >= chunk.length) {
                    chunk = stream.read();
                    offset = 0;
                    if (chunk == null || chunk.length == 0) {
                        throw new EOFException("Unexpected EOF while reading sync response");
                    }
                }
                int toCopy = Math.min(size - outOffset, chunk.length - offset);
                System.arraycopy(chunk, offset, out, outOffset, toCopy);
                offset += toCopy;
                outOffset += toCopy;
            }
            return out;
        }
    }
}

