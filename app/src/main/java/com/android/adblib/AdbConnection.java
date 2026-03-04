package com.android.adblib;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class AdbConnection implements Closeable {
    private Socket socket;
    private int lastLocalId;
    private volatile InputStream inputStream;
    volatile OutputStream outputStream;
    private volatile Thread connectionThread;
    private volatile boolean connectAttempted;
    private volatile boolean abortOnUnauthorised;
    private volatile boolean authorisationFailed;
    private volatile boolean connected;
    private volatile int maxData;
    private volatile AdbCrypto crypto;
    private boolean sentSignature;
    private volatile ConcurrentHashMap<Integer, AdbStream> openStreams;

    private AdbConnection() {
        openStreams = new ConcurrentHashMap<>();
        lastLocalId = 0;
        maxData = AdbProtocol.CONNECT_MAXDATA;
        connectionThread = createConnectionThread();
    }

    public static AdbConnection create(Socket socket, AdbCrypto crypto) throws IOException {
        AdbConnection connection = new AdbConnection();
        connection.crypto = crypto;
        connection.socket = socket;
        connection.inputStream = socket.getInputStream();
        connection.outputStream = socket.getOutputStream();
        socket.setTcpNoDelay(true);
        return connection;
    }

    private Thread createConnectionThread() {
        final AdbConnection conn = this;
        return new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int incomingMaxPayload = getIncomingMaxPayload();
                    AdbProtocol.AdbMessage msg = AdbProtocol.AdbMessage.parseAdbMessage(inputStream, incomingMaxPayload);
                    if (!AdbProtocol.validateMessage(msg, incomingMaxPayload)) {
                        throw new IOException("Invalid ADB packet header");
                    }

                    handleIncomingMessage(msg);
                }
            } catch (Exception ignored) {
                // Connection thread exits on any parse/socket/auth error.
            }

            synchronized (conn) {
                connected = false;
                cleanupStreams();
                conn.notifyAll();
                connectAttempted = false;
            }
        }, "AdbConnectionThread");
    }

    private void handleIncomingMessage(AdbProtocol.AdbMessage msg) throws Exception {
        switch (msg.command) {
            case AdbProtocol.CMD_OKAY:
                if (connected && msg.arg0 != 0 && msg.arg1 != 0) {
                    handleReady(msg);
                }
                break;
            case AdbProtocol.CMD_WRTE:
                if (connected && msg.arg0 != 0 && msg.arg1 != 0) {
                    handleWrite(msg);
                }
                break;
            case AdbProtocol.CMD_CLSE:
                if (connected && msg.arg1 != 0) {
                    handleClose(msg);
                }
                break;
            case AdbProtocol.CMD_OPEN:
                if (connected && msg.arg0 != 0 && msg.arg1 == 0) {
                    // This client does not support device-initiated services.
                    sendClose(0, msg.arg0);
                }
                break;
            case AdbProtocol.CMD_AUTH:
                handleAuth(msg);
                break;
            case AdbProtocol.CMD_CNXN:
                handleConnect(msg);
                break;
            default:
                // Keep behavior consistent with AOSP adb.cpp: ignore unknown commands.
                break;
        }
    }

    private void handleReady(AdbProtocol.AdbMessage msg) throws IOException {
        AdbStream stream = openStreams.get(msg.arg1);
        if (stream == null) {
            // Align with AOSP host behavior for stale local stream IDs.
            sendClose(msg.arg1, msg.arg0);
            return;
        }

        synchronized (stream) {
            if (!stream.updateRemoteId(msg.arg0)) {
                return;
            }
            stream.readyForWrite();
            stream.notifyAll();
        }
    }

    private void handleWrite(AdbProtocol.AdbMessage msg) throws IOException {
        AdbStream stream = openStreams.get(msg.arg1);
        if (stream == null) {
            return;
        }

        synchronized (stream) {
            if (!stream.matchesRemoteId(msg.arg0)) {
                return;
            }
            stream.addPayload(msg.payload);
            stream.sendReady();
        }
    }

    private void handleClose(AdbProtocol.AdbMessage msg) {
        AdbStream stream = openStreams.get(msg.arg1);
        if (stream == null) {
            return;
        }

        synchronized (stream) {
            if (!stream.canCloseWithRemoteId(msg.arg0)) {
                return;
            }
            openStreams.remove(msg.arg1, stream);
            stream.notifyClose(true);
        }
    }

    private void handleAuth(AdbProtocol.AdbMessage msg) throws Exception {
        if (msg.arg0 != AdbProtocol.AUTH_TYPE_TOKEN) {
            throw new IOException("Unsupported AUTH packet type: " + msg.arg0);
        }
        if (crypto == null) {
            throw new IOException("AdbCrypto is required for AUTH");
        }

        byte[] authReply;
        if (sentSignature) {
            if (abortOnUnauthorised) {
                authorisationFailed = true;
                throw new AdbAuthenticationFailedException();
            }
            authReply = AdbProtocol.generateAuth(
                    AdbProtocol.AUTH_TYPE_RSA_PUBLIC,
                    crypto.getAdbPublicKeyPayload()
            );
        } else {
            authReply = AdbProtocol.generateAuth(
                    AdbProtocol.AUTH_TYPE_SIGNATURE,
                    crypto.signAdbTokenPayload(msg.payload)
            );
            sentSignature = true;
        }

        synchronized (outputStream) {
            outputStream.write(authReply);
            outputStream.flush();
        }
    }

    private void handleConnect(AdbProtocol.AdbMessage msg) throws IOException {
        int negotiatedMaxData = Math.min(msg.arg1, AdbProtocol.CONNECT_MAXDATA);
        if (negotiatedMaxData <= 0) {
            throw new IOException("Invalid CNXN maxdata: " + msg.arg1);
        }

        synchronized (this) {
            maxData = negotiatedMaxData;
            connected = true;
            notifyAll();
        }
    }

    private int getIncomingMaxPayload() {
        int current = maxData;
        if (!connected || current <= 0) {
            return AdbProtocol.CONNECT_MAXDATA;
        }
        return current;
    }

    int getMaxDataValue() {
        int value = maxData;
        return value > 0 ? value : AdbProtocol.CONNECT_MAXDATA;
    }

    void unregisterStream(int localId, AdbStream stream) {
        openStreams.remove(localId, stream);
    }

    private void sendClose(int localId, int remoteId) throws IOException {
        if (remoteId == 0) {
            return;
        }
        byte[] packet = AdbProtocol.generateClose(localId, remoteId);
        synchronized (outputStream) {
            outputStream.write(packet);
            outputStream.flush();
        }
    }

    public int getMaxData() throws InterruptedException, IOException {
        if (!connectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }
        waitForConnection(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        return maxData;
    }

    public void connect() throws IOException, InterruptedException {
        connect(Long.MAX_VALUE, TimeUnit.MILLISECONDS, false);
    }

    public boolean connect(long timeout, TimeUnit unit, boolean abortOnUnauthorised)
            throws IOException, InterruptedException, AdbAuthenticationFailedException {
        if (connected || connectAttempted || connectionThread.getState() != Thread.State.NEW) {
            throw new IllegalStateException("connect() already called");
        }

        synchronized (outputStream) {
            outputStream.write(AdbProtocol.generateConnect());
            outputStream.flush();
        }

        connectAttempted = true;
        this.abortOnUnauthorised = abortOnUnauthorised;
        authorisationFailed = false;
        sentSignature = false;
        maxData = AdbProtocol.CONNECT_MAXDATA;
        connectionThread.start();
        return waitForConnection(timeout, unit);
    }

    public AdbStream open(String destination) throws IOException, InterruptedException {
        if (!connectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }
        waitForConnection(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

        int localId = nextLocalId();
        AdbStream stream = new AdbStream(this, localId);
        openStreams.put(localId, stream);
        try {
            synchronized (outputStream) {
                outputStream.write(AdbProtocol.generateOpen(localId, destination));
                outputStream.flush();
            }
        } catch (IOException e) {
            openStreams.remove(localId, stream);
            stream.notifyClose(false);
            throw e;
        }

        synchronized (stream) {
            while (!stream.isClosed() && !stream.isOpenAcknowledged()) {
                stream.wait();
            }
        }

        if (stream.isClosed()) {
            openStreams.remove(localId, stream);
            throw new ConnectException("Stream open actively rejected by remote peer");
        }
        return stream;
    }

    private synchronized int nextLocalId() {
        int candidate = ++lastLocalId;
        if (candidate == 0) {
            candidate = ++lastLocalId;
        }
        return candidate;
    }

    private boolean waitForConnection(long timeout, TimeUnit unit) throws InterruptedException, IOException {
        synchronized (this) {
            long timeoutMs = unit.toMillis(timeout);
            long now = System.currentTimeMillis();
            long deadline = timeoutMs >= Long.MAX_VALUE / 4 ? Long.MAX_VALUE : now + timeoutMs;
            while (!connected && connectAttempted) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                wait(remaining);
            }

            if (!connected) {
                if (connectAttempted) {
                    return false;
                }
                if (authorisationFailed) {
                    throw new AdbAuthenticationFailedException();
                }
                throw new IOException("Connection failed");
            }
        }
        return true;
    }

    private void cleanupStreams() {
        for (AdbStream stream : openStreams.values()) {
            stream.notifyClose(true);
        }
        openStreams.clear();
    }

    @Override
    public void close() throws IOException {
        if (connectionThread == null) {
            return;
        }

        socket.close();
        connectionThread.interrupt();
        try {
            connectionThread.join();
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
    }
}
