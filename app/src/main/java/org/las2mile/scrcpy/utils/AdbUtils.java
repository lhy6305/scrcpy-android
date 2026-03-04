package org.las2mile.scrcpy.utils;

import android.content.Context;
import android.util.Base64;

import com.android.adblib.AdbBase64;
import com.android.adblib.AdbConnection;
import com.android.adblib.AdbCrypto;
import com.android.adblib.AdbStream;

import java.io.Closeable;
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

    private static final int BASE64_APPEND_CHUNK_SIZE = 3000;
    private static final String BASE64_TMP_SUFFIX = ".b64";
    private static final String DEFAULT_REMOTE_FILE_MODE_OCTAL = "0644";

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
            String marker = "DONE_" + UUID.randomUUID().toString().replace("-", "");
            String wrappedCommand = "(" + (command == null ? "" : command) + "); echo " + marker;
            stream = openShellStream(adb, wrappedCommand);
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

            stream = openShellStream(adb, detachedCommand);
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
     * Pushes a file to the remote device through shell/base64 commands.
     * Some vendor adbd implementations do not ACK sync SEND/ DATA traffic reliably over direct TCP adblib.
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

        String remoteTmpPath = normalizedPath + BASE64_TMP_SUFFIX;
        String remotePathQuoted = shellQuote(normalizedPath);
        String remoteTmpQuoted = shellQuote(remoteTmpPath);

        executeShellCommandWait(adb,
                "rm -f " + remotePathQuoted + " " + remoteTmpQuoted,
                timeoutMs);

        String payload = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
        int offset = 0;
        while (offset < payload.length()) {
            checkCancelled();
            int end = Math.min(payload.length(), offset + BASE64_APPEND_CHUNK_SIZE);
            String chunk = payload.substring(offset, end);
            executeShellCommandWait(adb, buildAppendBase64LineCommand(chunk, remoteTmpPath), timeoutMs);
            offset = end;
        }

        String decodeCommand = "("
                + "(base64 -d < " + remoteTmpQuoted + " > " + remotePathQuoted + ")"
                + " || "
                + "(toybox base64 -d < " + remoteTmpQuoted + " > " + remotePathQuoted + ")"
                + ")"
                + " && chmod " + DEFAULT_REMOTE_FILE_MODE_OCTAL + " " + remotePathQuoted
                + " && rm -f " + remoteTmpQuoted;
        executeShellCommandWait(adb, decodeCommand, timeoutMs);
        executeShellCommandWait(adb, "test -s " + remotePathQuoted, timeoutMs);
    }

    static String buildAppendBase64LineCommand(String chunk, String targetFile) {
        String normalizedChunk = chunk == null ? "" : chunk;
        String normalizedTarget = targetFile == null ? "" : targetFile.trim();
        if (normalizedChunk.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("base64 chunk contains invalid single quote");
        }
        if (normalizedTarget.isEmpty()) {
            throw new IllegalArgumentException("targetFile is empty");
        }
        return "printf '%s\\n' '" + normalizedChunk + "' >> " + shellQuote(normalizedTarget);
    }

    private static String shellQuote(String value) {
        String normalized = value == null ? "" : value;
        return "'" + normalized.replace("'", "'\\''") + "'";
    }

    private static AdbStream openShellStream(AdbConnection adb, String command)
            throws IOException, InterruptedException {
        String normalized = command == null ? "" : command;
        synchronized (adb) {
            return adb.open("shell:" + normalized);
        }
    }
}

