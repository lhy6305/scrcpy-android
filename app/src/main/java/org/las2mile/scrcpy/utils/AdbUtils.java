package org.las2mile.scrcpy.utils;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.ByteArrayOutputStream;
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

    private static final String TAG = "AdbUtils";

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
     * Pushes a file to the remote device efficiently without matching generic prompts.
     */
    public static void pushFile(AdbConnection adb, byte[] fileBase64, String remotePath, long timeoutMs) throws IOException, InterruptedException {
        AdbStream shell = null;
        try {
            synchronized (adb) {
                shell = adb.open("shell:");
            }

                        // Remove existing file
                        String marker1 = "DEL_" + UUID.randomUUID().toString().replace("-", "");
                        shell.write(("rm -f " + remotePath + "\n").getBytes(StandardCharsets.UTF_8));
                        shell.write(("echo " + marker1 + "\n").getBytes(StandardCharsets.UTF_8));
                        if (!waitForMarker(shell, marker1, timeoutMs)) {
                            throw new SocketTimeoutException("Timeout waiting for remote file deletion");
                        }
            
                        String eofMarker = "EOF_" + UUID.randomUUID().toString().replace("-", "");
                        String cmd = "cat << '" + eofMarker + "' | base64 -d > " + remotePath + "\n";
                        shell.write(cmd.getBytes(StandardCharsets.UTF_8));
            
                        int len = fileBase64.length;
                        int chunkSize = 4056;
                        int offset = 0;
            
                                    // Chunked write
                                    while (offset < len) {
                                        checkCancelled();
                                        int currentChunk = Math.min(chunkSize, len - offset);
                                        byte[] chunk = new byte[currentChunk];
                                        System.arraycopy(fileBase64, offset, chunk, 0, currentChunk);
                                        shell.write(chunk);
                                        offset += currentChunk;
                                    }            
                        // End the cat block
                        shell.write(("\n" + eofMarker + "\n").getBytes(StandardCharsets.UTF_8));
            
                        // Wait for completion marker
                        String marker2 = "DONE_" + UUID.randomUUID().toString().replace("-", "");
                        shell.write(("echo " + marker2 + "\n").getBytes(StandardCharsets.UTF_8));            if (!waitForMarker(shell, marker2, timeoutMs)) {
                throw new SocketTimeoutException("Timeout waiting for file push completion");
            }
        } finally {
            closeQuietly(shell);
        }
    }
}
