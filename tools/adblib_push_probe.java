import com.android.adblib.AdbBase64;
import com.android.adblib.AdbConnection;
import com.android.adblib.AdbCrypto;
import com.android.adblib.AdbStream;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

public class adblib_push_probe {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final int SYNC_DATA_MAX = 64 * 1024;
    private static final int DEFAULT_REMOTE_FILE_MODE = 0644;
    private static final int BASE64_APPEND_CHUNK_SIZE = 3000;
    private static final String BASE64_TMP_SUFFIX = ".b64";
    private static final String DEFAULT_REMOTE_FILE_MODE_OCTAL = "0644";

    private static final class SyncStreamReader {
        private final AdbStream stream;
        private byte[] chunk = new byte[0];
        private int offset;

        SyncStreamReader(AdbStream stream) {
            this.stream = stream;
        }

        byte[] readFully(int size) throws IOException, InterruptedException {
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

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    private static boolean waitForMarker(AdbStream stream, String marker, long timeoutMs)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder tail = new StringBuilder();
        int markerLen = marker.length();
        while (System.currentTimeMillis() < deadline) {
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
            if (tail.length() > markerLen * 2) {
                tail.delete(0, tail.length() - markerLen * 2);
            }
        }
        return false;
    }

    private static void executeShellCommandWait(AdbConnection adb, String command, long timeoutMs)
            throws IOException, InterruptedException {
        AdbStream stream = null;
        try {
            synchronized (adb) {
                stream = adb.open("shell:");
            }
            String marker = "DONE_" + java.util.UUID.randomUUID().toString().replace("-", "");
            stream.write((command + " \n").getBytes(StandardCharsets.UTF_8));
            stream.write(("echo " + marker + " \n").getBytes(StandardCharsets.UTF_8));
            if (!waitForMarker(stream, marker, timeoutMs)) {
                throw new SocketTimeoutException("timeout waiting shell command: " + command);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }
    }

    private static String shellQuote(String value) {
        String normalized = value == null ? "" : value;
        return "'" + normalized.replace("'", "'\\''") + "'";
    }

    private static String buildAppendBase64LineCommand(String chunk, String targetFile) {
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

    private static void pushByShellBase64(AdbConnection adb, byte[] fileBytes, String remotePath, long timeoutMs)
            throws IOException, InterruptedException {
        String normalizedPath = remotePath == null ? "" : remotePath.trim();
        if (normalizedPath.isEmpty()) {
            throw new IOException("remotePath is empty");
        }
        String remoteTmpPath = normalizedPath + BASE64_TMP_SUFFIX;
        String remotePathQuoted = shellQuote(normalizedPath);
        String remoteTmpQuoted = shellQuote(remoteTmpPath);

        executeShellCommandWait(adb, "rm -f " + remotePathQuoted + " " + remoteTmpQuoted, timeoutMs);

        String payload = Base64.getEncoder().encodeToString(fileBytes);
        int offset = 0;
        int index = 0;
        while (offset < payload.length()) {
            int end = Math.min(payload.length(), offset + BASE64_APPEND_CHUNK_SIZE);
            String chunk = payload.substring(offset, end);
            if (index == 0 || index % 8 == 0) {
                log("[probe] shell chunk index=" + index);
            }
            executeShellCommandWait(adb, buildAppendBase64LineCommand(chunk, remoteTmpPath), timeoutMs);
            offset = end;
            index++;
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

    private static byte[] parsePemPrivateKey(File pemFile) throws IOException {
        String text = new String(Files.readAllBytes(pemFile.toPath()), StandardCharsets.US_ASCII);
        String normalized = text
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        if (normalized.isEmpty()) {
            throw new IOException("empty private key payload in " + pemFile);
        }
        return Base64.getDecoder().decode(normalized);
    }

    private static AdbCrypto loadAdbCryptoFromPem(File privPem, AdbBase64 b64) throws Exception {
        byte[] pkcs8 = parsePemPrivateKey(privPem);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        if (!(privateKey instanceof RSAPrivateCrtKey)) {
            throw new IOException("unsupported private key type: " + privateKey.getClass().getName());
        }
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
        RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
        KeyPair kp = new KeyPair(keyFactory.generatePublic(pubSpec), privateKey);
        return AdbCrypto.loadAdbKeyPair(b64, kp);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: adblib_push_probe <host> <local_file> <remote_path> [timeout_sec] [remote_mode] [socket_timeout_ms] [send_only] [mode=sync|shell]");
            System.exit(2);
        }
        String host = args[0];
        int port = 5555;
        String localFile = args[1];
        String remotePath = args[2];
        long timeoutSec = args.length >= 4 ? Long.parseLong(args[3]) : 30L;
        int remoteMode = args.length >= 5 ? Integer.decode(args[4]) : DEFAULT_REMOTE_FILE_MODE;
        int socketTimeoutMs = args.length >= 6 ? Integer.parseInt(args[5]) : SOCKET_TIMEOUT_MS;
        boolean sendOnly = args.length >= 7 && Boolean.parseBoolean(args[6]);
        String mode = args.length >= 8 ? args[7].trim().toLowerCase() : "sync";
        if (!"sync".equals(mode) && !"shell".equals(mode)) {
            throw new IllegalArgumentException("Unknown mode: " + mode + " (expected sync or shell)");
        }

        byte[] fileBytes = Files.readAllBytes(new File(localFile).toPath());
        AdbBase64 b64 = data -> Base64.getEncoder().encodeToString(data);
        File priv = new File(System.getProperty("user.home"), ".android/adbkey");
        AdbCrypto crypto = loadAdbCryptoFromPem(priv, b64);

        Socket sock = new Socket();
        AtomicReference<String> stage = new AtomicReference<>("init");
        Instant begin = Instant.now();
        try {
            stage.set("tcp_connect");
            sock.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(socketTimeoutMs);
            log("[probe] tcp connected");

            stage.set("adb_connect");
            AdbConnection adb = AdbConnection.create(sock, crypto);
            adb.connect();
            log("[probe] adb connected, maxData=" + adb.getMaxData());

            Thread worker = new Thread(() -> {
                AdbStream sync = null;
                try {
                    if ("shell".equals(mode)) {
                        stage.set("shell_push");
                        log("[probe] stage=" + stage.get());
                        pushByShellBase64(adb, fileBytes, remotePath, 10_000L);
                        stage.set("success");
                        log("[probe] shell push success");
                    } else {
                        stage.set("sync_open");
                        sync = adb.open("sync:");
                        int maxAdbPayload = adb.getMaxData();
                        int maxSyncDataPerPacket = Math.min(SYNC_DATA_MAX, Math.max(1, maxAdbPayload - 8));
                        log("[probe] sync open ok, maxSyncDataPerPacket=" + maxSyncDataPerPacket + ", bytes=" + fileBytes.length);

                        stage.set("sync_send_header");
                        log("[probe] stage=" + stage.get());
                        byte[] sendPath = (remotePath + "," + remoteMode).getBytes(StandardCharsets.UTF_8);
                        writeSyncRequest(sync, "SEND", sendPath);

                        if (sendOnly) {
                            stage.set("sync_send_only_read");
                            log("[probe] stage=" + stage.get() + " (waiting one read)");
                            byte[] resp = sync.read();
                            if (resp == null) {
                                throw new IOException("send-only read got null");
                            }
                            log("[probe] send-only read bytes=" + resp.length + " text=" + new String(resp, StandardCharsets.UTF_8));
                            stage.set("success");
                            return;
                        }

                        int offset = 0;
                        int index = 0;
                        while (offset < fileBytes.length) {
                            int chunkSize = Math.min(maxSyncDataPerPacket, fileBytes.length - offset);
                            byte[] chunk = new byte[chunkSize];
                            System.arraycopy(fileBytes, offset, chunk, 0, chunkSize);
                            stage.set("sync_data_" + index);
                            if (index == 0 || index % 8 == 0) {
                                log("[probe] stage=" + stage.get());
                            }
                            writeSyncRequest(sync, "DATA", chunk);
                            offset += chunkSize;
                            index++;
                        }
                        log("[probe] all DATA packets sent: " + index);

                        stage.set("sync_done");
                        writeSyncDone(sync, (int) (System.currentTimeMillis() / 1000L));

                        stage.set("sync_read_status");
                        log("[probe] stage=" + stage.get());
                        SyncStreamReader reader = new SyncStreamReader(sync);
                        byte[] header = reader.readFully(8);
                        String responseId = new String(header, 0, 4, StandardCharsets.US_ASCII);
                        int responseLength = decodeLittleEndianInt(header, 4);
                        if ("FAIL".equals(responseId)) {
                            byte[] payload = responseLength > 0 ? reader.readFully(responseLength) : new byte[0];
                            throw new IOException("sync FAIL: " + new String(payload, StandardCharsets.UTF_8));
                        }
                        if (!"OKAY".equals(responseId)) {
                            throw new IOException("unexpected sync response: " + responseId + "/" + responseLength);
                        }
                        if (responseLength > 0) {
                            reader.readFully(responseLength);
                        }

                        stage.set("success");
                        log("[probe] sync push success");
                    }
                } catch (Throwable t) {
                    stage.set("error@" + stage.get() + ":" + t.getClass().getSimpleName() + ":" + t.getMessage());
                    t.printStackTrace(System.out);
                } finally {
                    if (sync != null) {
                        try {
                            sync.close();
                        } catch (IOException ignore) {
                            // ignore
                        }
                    }
                }
            }, "push-worker");
            worker.start();

            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (worker.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            if (worker.isAlive()) {
                log("[probe] TIMEOUT stage=" + stage.get());
                for (StackTraceElement ste : worker.getStackTrace()) {
                    log("  at " + ste);
                }
                try {
                    adb.close();
                } catch (IOException ignore) {
                    // ignore
                }
                worker.join(3000);
                System.exit(1);
            }

            String doneStage = stage.get();
            if (!"success".equals(doneStage)) {
                log("[probe] FAILED stage=" + doneStage);
                try {
                    adb.close();
                } catch (IOException ignore) {
                    // ignore
                }
                System.exit(1);
            }

            Duration elapsed = Duration.between(begin, Instant.now());
            log("[probe] elapsed_ms=" + elapsed.toMillis());
            try {
                adb.close();
            } catch (SocketTimeoutException ignore) {
                // ignore
            }
        } finally {
            if (!sock.isClosed()) {
                try {
                    sock.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }
    }
}
