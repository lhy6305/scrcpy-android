package org.las2mile.scrcpy;


import static android.org.apache.commons.codec.binary.Base64.encodeBase64String;

import android.content.Context;
import android.util.Log;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Locale;


public class SendCommands {
    public enum Phase {
        CONNECTING_ADB,
        OPENING_SHELL,
        WAITING_SHELL,
        PUSHING_JAR,
        STARTING_SERVER,
    }

    public enum Error {
        NONE,
        CANCELLED,
        INVALID_HOST,
        CONNECTION_REFUSED,
        NO_ROUTE,
        TIMEOUT,
        KEY_ERROR,
        ADB_HANDSHAKE,
        SHELL_OPEN_FAILED,
        SHELL_PROMPT_TIMEOUT,
        IO,
        UNKNOWN,
    }

    public interface ProgressListener {
        void onProgress(Phase phase);
    }

    public static final class Result {
        public final boolean success;
        public final Error error;
        public final String message;

        private Result(boolean success, Error error, String message) {
            this.success = success;
            this.error = error;
            this.message = message;
        }

        public static Result ok() {
            return new Result(true, Error.NONE, null);
        }

        public static Result fail(Error error, String message) {
            return new Result(false, error, message);
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 5_000;
    private static final long PROMPT_TIMEOUT_MS = 10_000L;

    public static AdbBase64 getBase64Impl() {
        return new AdbBase64() {
            @Override
            public String encodeToString(byte[] arg0) {
                return encodeBase64String(arg0);
            }
        };
    }

    private static String buildServerStartCommand(int bitrate, int maxSize, int width, int height, boolean useAmlogicMode, int scid) {
        final StringBuilder command = new StringBuilder();
        command.append("CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 3.3.4");
        command.append(" log_level=info");
        command.append(String.format(Locale.US, " scid=%08x", scid & 0x7fffffff));
        command.append(" video=true");
        command.append(" video_source=display");
        command.append(" max_fps=30");
        command.append(" audio=true");
        command.append(" video_codec_options=repeat-previous-frame-after:long=0");
        command.append(" video_bit_rate=").append(bitrate);
        command.append(" control=true");
        command.append(" tunnel_forward=true");
        command.append(" max_size=").append(maxSize);
        // Keep server jar on device so stream-only reconnect can restart without re-push.
        command.append(" cleanup=false");
        if (useAmlogicMode) {
            command.append(" amlogic_v4l2=true");
            command.append(" amlogic_v4l2_instance=1");
            command.append(" amlogic_v4l2_source_type=1");
            command.append(" amlogic_v4l2_width=").append(width);
            command.append(" amlogic_v4l2_height=").append(height);
            command.append(" amlogic_v4l2_fps=30");
            command.append(" amlogic_v4l2_reqbufs=4");
            command.append(" amlogic_v4l2_format=nv21");
        }
        command.append(";");
        return buildDetachedShellCommand(command.toString());
    }

    private static String buildDetachedShellCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        return "(" + trimmed + ") >/dev/null 2>&1 &";
    }

    private static String buildAppendBase64LineCommand(String chunk, String targetFile) {
        if (chunk == null) {
            chunk = "";
        }
        if (chunk.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("base64 chunk must not contain single quote");
        }
        return " printf '%s\\n' '" + chunk + "' >> " + targetFile + "\n";
    }

    public Result sendAdbCommands(Context context, final byte[] fileBase64, final String ip, int bitrate, int maxSize,
            int width, int height, boolean useAmlogicMode, int scid, ProgressListener listener) {
        if (Thread.currentThread().isInterrupted()) {
            return Result.fail(Error.CANCELLED, "Cancelled");
        }

        // Use detached start so the shell channel can be closed safely after launch.
        final String command = buildServerStartCommand(bitrate, maxSize, width, height, useAmlogicMode, scid);

        try {
            adbWrite(context, ip, fileBase64, command, listener);
            return Result.ok();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail(Error.CANCELLED, "Cancelled");
        } catch (UnknownHostException e) {
            return Result.fail(Error.INVALID_HOST, e.getMessage());
        } catch (ConnectException e) {
            return Result.fail(Error.CONNECTION_REFUSED, e.getMessage());
        } catch (NoRouteToHostException e) {
            return Result.fail(Error.NO_ROUTE, e.getMessage());
        } catch (SocketTimeoutException e) {
            return Result.fail(Error.TIMEOUT, e.getMessage());
        } catch (IOException e) {
            return Result.fail(Error.IO, e.getMessage());
        } catch (RuntimeException e) {
            return Result.fail(Error.UNKNOWN, e.getMessage());
        }
    }

    public Result startServerOnly(Context context, final String ip, int bitrate, int maxSize,
            int width, int height, boolean useAmlogicMode, int scid, ProgressListener listener) {
        if (Thread.currentThread().isInterrupted()) {
            return Result.fail(Error.CANCELLED, "Cancelled");
        }

        // Use detached start so reconnect does not keep stale shell channels alive.
        final String command = buildServerStartCommand(bitrate, maxSize, width, height, useAmlogicMode, scid);

        // Some devices are very sensitive to reconnect timing (especially when ADB over TCP is single-connection).
        // Retry a few times to let the previous connection fully close.
        final int maxAttempts = 6;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                adbStartOnly(context, ip, command, listener);
                return Result.ok();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.fail(Error.CANCELLED, "Cancelled");
            } catch (UnknownHostException e) {
                return Result.fail(Error.INVALID_HOST, e.getMessage());
            } catch (NoRouteToHostException e) {
                return Result.fail(Error.NO_ROUTE, e.getMessage());
            } catch (ConnectException e) {
                if (attempt == maxAttempts) {
                    return Result.fail(Error.CONNECTION_REFUSED, e.getMessage());
                }
                sleepBackoff(attempt);
            } catch (SocketTimeoutException e) {
                if (attempt == maxAttempts) {
                    return Result.fail(Error.TIMEOUT, e.getMessage());
                }
                sleepBackoff(attempt);
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    return Result.fail(Error.IO, e.getMessage());
                }
                sleepBackoff(attempt);
            } catch (RuntimeException e) {
                if (attempt == maxAttempts) {
                    return Result.fail(Error.UNKNOWN, e.getMessage());
                }
                sleepBackoff(attempt);
            }
        }
        return Result.fail(Error.UNKNOWN, "Unknown");
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(150L * attempt);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
    }

    private static void report(ProgressListener listener, Phase phase) {
        if (listener != null) {
            listener.onProgress(phase);
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Cancelled");
        }
    }

    private static AdbCrypto setupCrypto(Context context) throws IOException {
        AdbCrypto c;
        try {
            c = AdbCrypto.loadAdbKeyPair(getBase64Impl(), context.getFileStreamPath("priv.key"), context.getFileStreamPath("pub.key"));
        } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException | NullPointerException e) {
            // Failed to read from file
            c = null;
        }

        if (c == null) {
            // We couldn't load a key, so let's generate a new one
            try {
                c = AdbCrypto.generateAdbKeyPair(getBase64Impl());
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("Failed to generate adb key pair", e);
            }
            // Save it
            c.saveAdbKeyPair(context.getFileStreamPath("priv.key"), context.getFileStreamPath("pub.key"));
        }

        return c;
    }

    private static void adbWrite(Context context, String ip, byte[] fileBase64, String command, ProgressListener listener)
            throws IOException, InterruptedException {
        checkCancelled();
        AdbCrypto crypto = setupCrypto(context);

        report(listener, Phase.CONNECTING_ADB);
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(ip, 5555), CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            Log.i("scrcpy", "ADB socket connection successful");
        } catch (UnknownHostException e) {
            throw new UnknownHostException(ip + " is not a valid host");
        } catch (ConnectException e) {
            throw new ConnectException("Device at " + ip + ":" + 5555 + " refused connection");
        } catch (NoRouteToHostException e) {
            throw new NoRouteToHostException("Couldn't find adb device at " + ip + ":" + 5555);
        }

        AdbConnection adb = null;
        AdbStream stream = null;
        try {
            adb = AdbConnection.create(sock, crypto);
            adb.connect();
        } catch (IOException | InterruptedException e) {
            closeQuietly(sock);
            throw e;
        } catch (IllegalStateException e) {
            closeQuietly(sock);
            throw new IOException("ADB connection in illegal state", e);
        }

        try {
            report(listener, Phase.OPENING_SHELL);
            stream = adb.open("shell:");

            report(listener, Phase.WAITING_SHELL);
            stream.write(" \n");
            if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }

            report(listener, Phase.PUSHING_JAR);
            int len = fileBase64.length;
            byte[] filePart = new byte[4056];
            int sourceOffset = 0;

            stream.write(" command -v pkill >/dev/null 2>&1 && pkill -f com.genymobile.scrcpy.Server || true\n");
            if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }
            stream.write(" cd /data/local/tmp\n");
            if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }
            stream.write(" rm -f serverBase64\n");
            if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }

            while (sourceOffset < len) {
                checkCancelled();
                if (len - sourceOffset >= 4056) {
                    System.arraycopy(fileBase64, sourceOffset, filePart, 0, 4056);
                    sourceOffset = sourceOffset + 4056;
                    String serverBase64Part = new String(filePart, StandardCharsets.US_ASCII);
                    stream.write(buildAppendBase64LineCommand(serverBase64Part, "serverBase64"));
                    if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                        throw new SocketTimeoutException("Timed out waiting for shell prompt");
                    }
                } else {
                    int rem = len - sourceOffset;
                    byte[] remPart = new byte[rem];
                    System.arraycopy(fileBase64, sourceOffset, remPart, 0, rem);
                    sourceOffset = sourceOffset + rem;
                    String serverBase64Part = new String(remPart, StandardCharsets.US_ASCII);
                    stream.write(buildAppendBase64LineCommand(serverBase64Part, "serverBase64"));
                    if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                        throw new SocketTimeoutException("Timed out waiting for shell prompt");
                    }
                }
            }
            stream.write(" base64 -d < serverBase64 > scrcpy-server.jar && rm serverBase64\n");
            if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }

            report(listener, Phase.STARTING_SERVER);
            stream.write(command + '\n');
        } finally {
            closeQuietly(stream);
            closeQuietly(adb);
            closeQuietly(sock);
        }
    }

    private static void adbStartOnly(Context context, String ip, String command, ProgressListener listener)
            throws IOException, InterruptedException {
        checkCancelled();
        AdbCrypto crypto = setupCrypto(context);

        report(listener, Phase.CONNECTING_ADB);
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(ip, 5555), CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            Log.i("scrcpy", "ADB socket connection successful");
        } catch (UnknownHostException e) {
            throw new UnknownHostException(ip + " is not a valid host");
        } catch (ConnectException e) {
            throw new ConnectException("Device at " + ip + ":" + 5555 + " refused connection");
        } catch (NoRouteToHostException e) {
            throw new NoRouteToHostException("Couldn't find adb device at " + ip + ":" + 5555);
        }

        AdbConnection adb = null;
        AdbStream stream = null;
        try {
            adb = AdbConnection.create(sock, crypto);
            adb.connect();
        } catch (IOException | InterruptedException e) {
            closeQuietly(sock);
            throw e;
        } catch (IllegalStateException e) {
            closeQuietly(sock);
            throw new IOException("ADB connection in illegal state", e);
        }
        try {
            report(listener, Phase.OPENING_SHELL);
            stream = adb.open("shell:");

            report(listener, Phase.WAITING_SHELL);
            stream.write(" \n");
            if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }

            stream.write(" command -v pkill >/dev/null 2>&1 && pkill -f com.genymobile.scrcpy.Server || true\n");
            if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }
            stream.write(" cd /data/local/tmp\n");
            if (!waitForPrompt(stream, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }

            report(listener, Phase.STARTING_SERVER);
            stream.write(command + '\n');
        } finally {
            closeQuietly(stream);
            closeQuietly(adb);
            closeQuietly(sock);
        }
    }

    private static void closeQuietly(Socket sock) {
        if (sock == null) {
            return;
        }
        try {
            sock.close();
        } catch (IOException ignore) {
            // ignore
        }
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

    private static boolean waitForPrompt(AdbStream stream, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String tail = "";
        while (System.currentTimeMillis() < deadline) {
            checkCancelled();
            byte[] responseBytes;
            try {
                responseBytes = stream.read();
            } catch (SocketTimeoutException e) {
                // Continue polling until deadline.
                continue;
            }
            if (responseBytes == null || responseBytes.length == 0) {
                continue;
            }
            String response = new String(responseBytes, StandardCharsets.US_ASCII);
            String combined = tail + response;
            if (combined.endsWith("$ ") || combined.endsWith("# ")) {
                return true;
            }
            if (combined.length() >= 2) {
                tail = combined.substring(combined.length() - 2);
            } else {
                tail = combined;
            }
        }
        return false;
    }

}
