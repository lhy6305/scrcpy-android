package com.android.adblib;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    private volatile boolean openAcknowledged;

    public AdbStream(AdbConnection adbConn, int localId) {
        this.adbConn = adbConn;
        this.localId = localId;
        this.readQueue = new ConcurrentLinkedQueue<>();
        this.writeReady = new AtomicBoolean(false);
        this.isClosed = false;
        this.openAcknowledged = false;
    }

    void addPayload(byte[] payload) {
        synchronized (readQueue) {
            readQueue.add(payload);
            readQueue.notifyAll();
        }
    }

    void sendReady() throws IOException {
        if (isClosed || remoteId == 0) {
            return;
        }
        byte[] packet = AdbProtocol.generateReady(localId, remoteId);
        synchronized (adbConn.outputStream) {
            adbConn.outputStream.write(packet);
            adbConn.outputStream.flush();
        }
    }

    boolean updateRemoteId(int remoteId) {
        if (remoteId == 0) {
            return false;
        }
        if (this.remoteId == 0) {
            this.remoteId = remoteId;
            this.openAcknowledged = true;
            return true;
        }
        return this.remoteId == remoteId;
    }

    void readyForWrite() {
        writeReady.set(true);
    }

    boolean isOpenAcknowledged() {
        return openAcknowledged;
    }

    boolean matchesRemoteId(int remoteId) {
        return remoteId != 0 && this.remoteId != 0 && this.remoteId == remoteId;
    }

    boolean canCloseWithRemoteId(int remoteId) {
        return remoteId == 0 || matchesRemoteId(remoteId);
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

            if (data != null && pendingClose && readQueue.isEmpty()) {
                isClosed = true;
                synchronized (this) {
                    notifyAll();
                }
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
        byte[] source = payload == null ? new byte[0] : payload;
        int maxChunkSize = Math.max(1, adbConn.getMaxDataValue());

        int offset = 0;
        do {
            awaitReadyForWrite();

            int chunkSize = Math.min(maxChunkSize, source.length - offset);
            byte[] chunk;
            if (source.length == 0) {
                chunk = source;
            } else if (offset == 0 && chunkSize == source.length) {
                chunk = source;
            } else {
                chunk = Arrays.copyOfRange(source, offset, offset + chunkSize);
            }

            byte[] packet = AdbProtocol.generateWrite(localId, remoteId, chunk);
            synchronized (adbConn.outputStream) {
                adbConn.outputStream.write(packet);
                if (flush && (source.length == 0 || offset + chunkSize >= source.length)) {
                    adbConn.outputStream.flush();
                }
            }

            offset += chunkSize;
        } while (offset < source.length);
    }

    private void awaitReadyForWrite() throws IOException, InterruptedException {
        synchronized (this) {
            while (!isClosed && !writeReady.compareAndSet(true, false)) {
                wait();
            }
            if (isClosed) {
                throw new IOException("Stream closed");
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

        adbConn.unregisterStream(localId, this);

        if (remoteId == 0) {
            return;
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
