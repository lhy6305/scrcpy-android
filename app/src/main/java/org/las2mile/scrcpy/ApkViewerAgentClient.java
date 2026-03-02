package org.las2mile.scrcpy;

import android.content.Context;
import android.util.Base64;

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

/**
 * Deploys and executes apkviewer-agent on the remote device over ADB TCP.
 *
 * Agent is packaged as an APK, renamed to a ".jar" and executed via:
 * <pre>
 * CLASSPATH=/data/local/tmp/apkviewer-agent.jar app_process / org.las2mile.apkviewer.AgentMain ...
 * </pre>
 */
public final class ApkViewerAgentClient {
    public static final String ASSET_NAME = "apkviewer-agent.jar";
    public static final String REMOTE_JAR_PATH = "/data/local/tmp/apkviewer-agent.jar";

    private static final int ADB_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 20_000;
    private static final long PROMPT_TIMEOUT_MS = 10_000L;

    private ApkViewerAgentClient() {
        // no instances
    }

    public interface ProgressListener {
        void onMessage(String message);
    }

    public static void deploy(Context context, String ip, byte[] agentJarBase64, ProgressListener listener)
            throws IOException, InterruptedException {
        if (agentJarBase64 == null || agentJarBase64.length == 0) {
            throw new IOException("agentJarBase64 is empty");
        }

        AdbSession session = null;
        try {
            session = AdbSession.connect(context, ip);
            AdbStream shell = session.adb.open("shell:");

            report(listener, "Opening shell...");
            shell.write(" \n");
            if (!waitForPrompt(shell, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }

            report(listener, "Pushing agent...");
            shell.write(" cd /data/local/tmp\n");
            if (!waitForPrompt(shell, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }

            shell.write(" rm -f apkviewerAgentBase64\n");
            if (!waitForPrompt(shell, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }

            int len = agentJarBase64.length;
            byte[] filePart = new byte[4056];
            int sourceOffset = 0;
            while (sourceOffset < len) {
                checkCancelled();
                if (len - sourceOffset >= filePart.length) {
                    System.arraycopy(agentJarBase64, sourceOffset, filePart, 0, filePart.length);
                    sourceOffset += filePart.length;
                    String part = new String(filePart, StandardCharsets.US_ASCII);
                    shell.write(" echo " + part + " >> apkviewerAgentBase64\n");
                } else {
                    int rem = len - sourceOffset;
                    byte[] remPart = new byte[rem];
                    System.arraycopy(agentJarBase64, sourceOffset, remPart, 0, rem);
                    sourceOffset += rem;
                    String part = new String(remPart, StandardCharsets.US_ASCII);
                    shell.write(" echo " + part + " >> apkviewerAgentBase64\n");
                }

                if (!waitForPrompt(shell, PROMPT_TIMEOUT_MS)) {
                    throw new SocketTimeoutException("Timed out waiting for shell prompt");
                }
            }

            shell.write(" base64 -d < apkviewerAgentBase64 > apkviewer-agent.jar && rm apkviewerAgentBase64\n");
            if (!waitForPrompt(shell, PROMPT_TIMEOUT_MS)) {
                throw new SocketTimeoutException("Timed out waiting for shell prompt");
            }
        } finally {
            closeQuietly(session);
        }
    }

    public static String exec(Context context, String ip, String command) throws IOException, InterruptedException {
        if (command == null) {
            throw new IOException("command is null");
        }
        AdbSession session = null;
        try {
            session = AdbSession.connect(context, ip);
            AdbStream stream = session.adb.open("shell:" + command);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (!Thread.currentThread().isInterrupted()) {
                byte[] chunk = stream.read();
                if (chunk == null) {
                    break;
                }
                if (chunk.length == 0) {
                    continue;
                }
                out.write(chunk, 0, chunk.length);
            }
            checkCancelled();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            closeQuietly(session);
        }
    }

    public static String execAgent(Context context, String ip, String args) throws IOException, InterruptedException {
        String cmd = "CLASSPATH=" + REMOTE_JAR_PATH + " app_process / org.las2mile.apkviewer.AgentMain " + args;
        return exec(context, ip, cmd);
    }

    private static void report(ProgressListener listener, String message) {
        if (listener != null) {
            listener.onMessage(message);
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Cancelled");
        }
    }

    private static boolean waitForPrompt(AdbStream stream, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String tail = "";
        while (System.currentTimeMillis() < deadline) {
            checkCancelled();
            byte[] responseBytes = stream.read();
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

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignore) {
            // ignore
        }
    }

    private static final class AdbSession implements Closeable {
        final Socket socket;
        final AdbConnection adb;

        private AdbSession(Socket socket, AdbConnection adb) {
            this.socket = socket;
            this.adb = adb;
        }

        static AdbSession connect(Context context, String ip) throws IOException, InterruptedException {
            AdbCrypto crypto = setupCrypto(context);
            Socket sock = new Socket();
            try {
                sock.connect(new InetSocketAddress(ip, ADB_PORT), CONNECT_TIMEOUT_MS);
                sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            } catch (UnknownHostException e) {
                throw new UnknownHostException(ip + " is not a valid host");
            } catch (ConnectException e) {
                throw new ConnectException("Device at " + ip + ":" + ADB_PORT + " refused connection");
            }

            AdbConnection adb = null;
            try {
                adb = AdbConnection.create(sock, crypto);
                adb.connect();
                return new AdbSession(sock, adb);
            } catch (IOException | InterruptedException e) {
                closeSocketQuietly(sock);
                throw e;
            } catch (RuntimeException e) {
                closeSocketQuietly(sock);
                throw new IOException("ADB connection failed", e);
            }
        }

        @Override
        public void close() throws IOException {
            closeSocketQuietly(socket);
        }

        private static void closeSocketQuietly(Socket sock) {
            if (sock == null) {
                return;
            }
            try {
                sock.close();
            } catch (IOException ignore) {
                // ignore
            }
        }
    }

    private static AdbCrypto setupCrypto(Context context) throws IOException {
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

    private static AdbBase64 getBase64Impl() {
        return new AdbBase64() {
            @Override
            public String encodeToString(byte[] data) {
                return Base64.encodeToString(data, Base64.NO_WRAP);
            }
        };
    }
}
