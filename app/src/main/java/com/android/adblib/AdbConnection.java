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
                while (!connectionThread.isInterrupted()) {
                    AdbProtocol.AdbMessage msg = AdbProtocol.AdbMessage.parseAdbMessage(inputStream);
                    if (!AdbProtocol.validateMessage(msg)) {
                        continue;
                    }

                    switch (msg.command) {
                        case AdbProtocol.CMD_OKAY:
                        case AdbProtocol.CMD_WRTE:
                        case AdbProtocol.CMD_CLSE:
                            if (!connected) {
                                continue;
                            }

                            AdbStream stream = openStreams.get(msg.arg1);
                            if (stream == null) {
                                continue;
                            }

                            synchronized (stream) {
                                if (msg.command == AdbProtocol.CMD_OKAY) {
                                    stream.updateRemoteId(msg.arg0);
                                    stream.readyForWrite();
                                    stream.notify();
                                } else if (msg.command == AdbProtocol.CMD_WRTE) {
                                    stream.addPayload(msg.payload);
                                    stream.sendReady();
                                } else if (msg.command == AdbProtocol.CMD_CLSE) {
                                    openStreams.remove(msg.arg1);
                                    stream.notifyClose(true);
                                }
                            }
                            break;
                        case AdbProtocol.CMD_AUTH:
                            if (msg.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) {
                                byte[] authReply;
                                if (sentSignature) {
                                    if (abortOnUnauthorised) {
                                        authorisationFailed = true;
                                        throw new RuntimeException();
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
                            break;
                        case AdbProtocol.CMD_CNXN:
                            synchronized (conn) {
                                maxData = msg.arg1;
                                connected = true;
                                conn.notifyAll();
                            }
                            break;
                        default:
                            break;
                    }
                }
            } catch (Exception ignored) {
                // Connection thread exits on any parse/socket/auth error.
            }

            synchronized (conn) {
                cleanupStreams();
                conn.notifyAll();
                connectAttempted = false;
            }
        });
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
        if (connected) {
            throw new IllegalStateException("Already connected");
        }

        synchronized (outputStream) {
            outputStream.write(AdbProtocol.generateConnect());
            outputStream.flush();
        }

        connectAttempted = true;
        this.abortOnUnauthorised = abortOnUnauthorised;
        authorisationFailed = false;
        connectionThread.start();
        return waitForConnection(timeout, unit);
    }

    public AdbStream open(String destination) throws IOException, InterruptedException {
        int localId = ++lastLocalId;
        if (!connectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }
        waitForConnection(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

        AdbStream stream = new AdbStream(this, localId);
        openStreams.put(localId, stream);
        synchronized (outputStream) {
            outputStream.write(AdbProtocol.generateOpen(localId, destination));
            outputStream.flush();
        }

        synchronized (stream) {
            stream.wait();
        }

        if (stream.isClosed()) {
            throw new ConnectException("Stream open actively rejected by remote peer");
        }
        return stream;
    }

    private boolean waitForConnection(long timeout, TimeUnit unit) throws InterruptedException, IOException {
        synchronized (this) {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (!connected && connectAttempted && deadline - System.currentTimeMillis() > 0) {
                wait(deadline - System.currentTimeMillis());
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
            try {
                stream.close();
            } catch (IOException ignore) {
                // ignore
            }
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
