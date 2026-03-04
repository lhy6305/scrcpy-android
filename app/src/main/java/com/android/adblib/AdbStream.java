package com.android.adblib;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class AdbStream implements Closeable {
    private final AdbConnection adbConn;
    private final int localId;
    private volatile int remoteId;
    private final AtomicBoolean writeReady;
    private final Queue<byte[]> readQueue;
    private volatile boolean isClosed;
    private volatile boolean pendingClose;

    public AdbStream(AdbConnection adbConn, int localId) {
        this.adbConn = adbConn;
        this.localId = localId;
        this.readQueue = new ConcurrentLinkedQueue<>();
        this.writeReady = new AtomicBoolean(false);
        this.isClosed = false;
    }

    void addPayload(byte[] payload) {
        synchronized (readQueue) {
            readQueue.add(payload);
            readQueue.notifyAll();
        }
    }

    void sendReady() throws IOException {
        byte[] packet = AdbProtocol.generateReady(localId, remoteId);
        synchronized (adbConn.outputStream) {
            adbConn.outputStream.write(packet);
            adbConn.outputStream.flush();
        }
    }

    void updateRemoteId(int remoteId) {
        this.remoteId = remoteId;
    }

    void readyForWrite() {
        writeReady.set(true);
    }

    void notifyClose(boolean notifyReader) {
        if (notifyReader && !readQueue.isEmpty()) {
            pendingClose = true;
        } else {
            isClosed = true;
        }
        synchronized (this) {
            notifyAll();
        }
        synchronized (readQueue) {
            readQueue.notifyAll();
        }
    }

    public byte[] read() throws InterruptedException, IOException {
        byte[] data;
        synchronized (readQueue) {
            while ((data = readQueue.poll()) == null && !isClosed) {
                readQueue.wait();
            }

            if (isClosed) {
                throw new IOException("Stream closed");
            }

            if (pendingClose && readQueue.isEmpty()) {
                isClosed = true;
            }
        }
        return data;
    }

    public void write(String payload) throws IOException, InterruptedException {
        write(payload.getBytes(StandardCharsets.UTF_8), false);
        write(new byte[]{0}, true);
    }

    public void write(byte[] payload) throws IOException, InterruptedException {
        write(payload, true);
    }

    public void write(byte[] payload, boolean flush) throws IOException, InterruptedException {
        synchronized (this) {
            while (!isClosed && !writeReady.compareAndSet(true, false)) {
                wait();
            }
            if (isClosed) {
                throw new IOException("Stream closed");
            }
        }

        byte[] packet = AdbProtocol.generateWrite(localId, remoteId, payload);
        synchronized (adbConn.outputStream) {
            adbConn.outputStream.write(packet);
            if (flush) {
                adbConn.outputStream.flush();
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (isClosed) {
                return;
            }
            notifyClose(false);
        }

        byte[] packet = AdbProtocol.generateClose(localId, remoteId);
        synchronized (adbConn.outputStream) {
            adbConn.outputStream.write(packet);
            adbConn.outputStream.flush();
        }
    }

    public boolean isClosed() {
        return isClosed;
    }
}
