package com.android.adblib;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AdbConnectionBehaviorTest {

    @Test
    public void protocolConstants_alignWithAospAndroid9Adb() {
        assertEquals(0x45534c43, AdbProtocol.CMD_CLSE);
        assertEquals(0x45545257, AdbProtocol.CMD_WRTE);
        assertEquals(0x01000000, AdbProtocol.CONNECT_VERSION_MIN);
        assertEquals(0x01000001, AdbProtocol.CONNECT_VERSION_SKIP_CHECKSUM);
        assertEquals(0x01000001, AdbProtocol.CONNECT_VERSION);
        assertEquals(1024 * 1024, AdbProtocol.CONNECT_MAXDATA);
    }

    @Test
    public void parseAdbMessage_rejectsPayloadLargerThanNegotiatedMax() throws Exception {
        ByteBuffer header = ByteBuffer.allocate(AdbProtocol.ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(AdbProtocol.CMD_WRTE);
        header.putInt(1);
        header.putInt(2);
        header.putInt(AdbProtocol.MAX_PAYLOAD + 1);
        header.putInt(0);
        header.putInt(AdbProtocol.CMD_WRTE ^ -1);

        try {
            AdbProtocol.AdbMessage.parseAdbMessage(
                    new ByteArrayInputStream(header.array()),
                    AdbProtocol.MAX_PAYLOAD
            );
            fail("Expected oversized payload to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Invalid payload length"));
        }
    }

    @Test
    public void unknownOkayForMissingLocalStream_sendsCloseBack() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 3000);
            client.setSoTimeout(3000);

            ExecutorService executor = Executors.newFixedThreadPool(1);
            try {
                CountDownLatch connectDone = new CountDownLatch(1);
                Future<AdbProtocol.AdbMessage> serverFuture = executor.submit(() -> {
                    try (Socket peer = server.accept()) {
                        peer.setSoTimeout(3000);
                        AdbProtocol.AdbMessage connectPacket =
                                AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream(), AdbProtocol.MAX_PAYLOAD);
                        assertEquals(AdbProtocol.CMD_CNXN, connectPacket.command);

                        // CNXN checksum 0 is valid in checksum-skip protocol mode.
                        writePacket(
                                peer.getOutputStream(),
                                AdbProtocol.CMD_CNXN,
                                AdbProtocol.CONNECT_VERSION,
                                1024,
                                "device::test\0".getBytes(StandardCharsets.UTF_8),
                                0
                        );

                        // Wait until the client finishes connect() before forcing stream-id cleanup logic.
                        connectDone.await(3, TimeUnit.SECONDS);

                        // Unknown local stream id on client side.
                        writePacket(peer.getOutputStream(), AdbProtocol.CMD_OKAY, 7, 999, null, 0);
                        return AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream(), AdbProtocol.MAX_PAYLOAD);
                    }
                });

                AdbConnection connection = AdbConnection.create(client, null);
                try {
                    assertTrue(connection.connect(3, TimeUnit.SECONDS, false));
                    connectDone.countDown();

                    AdbProtocol.AdbMessage close = serverFuture.get(5, TimeUnit.SECONDS);
                    assertEquals(AdbProtocol.CMD_CLSE, close.command);
                    assertEquals(999, close.arg0);
                    assertEquals(7, close.arg1);
                } finally {
                    connection.close();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void rejectedOpenDoesNotBlockWaitingThread() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 3000);
            client.setSoTimeout(3000);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> serverFuture = executor.submit(() -> {
                    try (Socket peer = server.accept()) {
                        peer.setSoTimeout(3000);

                        AdbProtocol.AdbMessage connectPacket =
                                AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream(), AdbProtocol.MAX_PAYLOAD);
                        assertEquals(AdbProtocol.CMD_CNXN, connectPacket.command);

                        writePacket(
                                peer.getOutputStream(),
                                AdbProtocol.CMD_CNXN,
                                AdbProtocol.CONNECT_VERSION,
                                1024,
                                "device::test\0".getBytes(StandardCharsets.UTF_8),
                                0
                        );

                        AdbProtocol.AdbMessage openPacket =
                                AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream(), AdbProtocol.MAX_PAYLOAD);
                        assertEquals(AdbProtocol.CMD_OPEN, openPacket.command);

                        // Reject open: CLOSE(0, remote-id).
                        writePacket(peer.getOutputStream(), AdbProtocol.CMD_CLSE, 0, openPacket.arg0, null, 0);
                    }
                    return null;
                });

                AdbConnection connection = AdbConnection.create(client, null);
                try {
                    assertTrue(connection.connect(3, TimeUnit.SECONDS, false));

                    Future<AdbStream> openFuture = executor.submit(() -> connection.open("shell:id"));
                    try {
                        openFuture.get(3, TimeUnit.SECONDS);
                        fail("Expected ConnectException");
                    } catch (ExecutionException expected) {
                        assertTrue(expected.getCause() instanceof ConnectException);
                    } catch (TimeoutException e) {
                        fail("open() timed out instead of failing fast on CLOSE");
                    }

                    serverFuture.get(3, TimeUnit.SECONDS);
                } finally {
                    connection.close();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void openPacketChecksum_zeroWhenPeerNegotiatesSkipChecksum() throws Exception {
        AdbProtocol.AdbMessage openPacket =
                connectAndCaptureOpenPacket(AdbProtocol.CONNECT_VERSION, 1024, 0);
        assertEquals(0, openPacket.checksum);
    }

    @Test
    public void openPacketChecksum_computedForLegacyProtocolVersion() throws Exception {
        AdbProtocol.AdbMessage openPacket =
                connectAndCaptureOpenPacket(AdbProtocol.CONNECT_VERSION_MIN, 1024, null);
        assertEquals(payloadChecksum(openPacket.payload), openPacket.checksum);
    }

    @Test
    public void connectNegotiatesUnsignedMaxDataLikeAospTransport() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 3000);
            client.setSoTimeout(3000);

            ExecutorService executor = Executors.newFixedThreadPool(1);
            try {
                CountDownLatch assertionsDone = new CountDownLatch(1);
                Future<?> serverFuture = executor.submit(() -> {
                    try (Socket peer = server.accept()) {
                        peer.setSoTimeout(3000);
                        AdbProtocol.AdbMessage connectPacket =
                                AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream(), AdbProtocol.MAX_PAYLOAD);
                        assertEquals(AdbProtocol.CMD_CNXN, connectPacket.command);

                        writePacket(
                                peer.getOutputStream(),
                                AdbProtocol.CMD_CNXN,
                                AdbProtocol.CONNECT_VERSION,
                                -1,
                                "device::test\0".getBytes(StandardCharsets.UTF_8),
                                0
                        );

                        // Keep the socket alive until client-side assertions complete.
                        assertionsDone.await(5, TimeUnit.SECONDS);
                    }
                    return null;
                });

                AdbConnection connection = AdbConnection.create(client, null);
                try {
                    assertTrue(connection.connect(3, TimeUnit.SECONDS, false));
                    assertEquals(AdbProtocol.CONNECT_MAXDATA, connection.getMaxData());
                    assertionsDone.countDown();
                    serverFuture.get(3, TimeUnit.SECONDS);
                } finally {
                    assertionsDone.countDown();
                    connection.close();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static AdbProtocol.AdbMessage connectAndCaptureOpenPacket(int peerVersion, int peerMaxData,
                                                                       Integer peerConnectChecksumOverride)
            throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 3000);
            client.setSoTimeout(3000);

            ExecutorService executor = Executors.newFixedThreadPool(1);
            try {
                Future<AdbProtocol.AdbMessage> serverFuture = executor.submit(() -> {
                    try (Socket peer = server.accept()) {
                        peer.setSoTimeout(3000);
                        AdbProtocol.AdbMessage connectPacket =
                                AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream(), AdbProtocol.MAX_PAYLOAD);
                        assertEquals(AdbProtocol.CMD_CNXN, connectPacket.command);

                        writePacket(
                                peer.getOutputStream(),
                                AdbProtocol.CMD_CNXN,
                                peerVersion,
                                peerMaxData,
                                "device::test\0".getBytes(StandardCharsets.UTF_8),
                                peerConnectChecksumOverride
                        );

                        AdbProtocol.AdbMessage openPacket =
                                AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream(), AdbProtocol.MAX_PAYLOAD);
                        assertEquals(AdbProtocol.CMD_OPEN, openPacket.command);

                        writePacket(peer.getOutputStream(), AdbProtocol.CMD_CLSE, 0, openPacket.arg0, null, 0);
                        return openPacket;
                    }
                });

                AdbConnection connection = AdbConnection.create(client, null);
                try {
                    assertTrue(connection.connect(3, TimeUnit.SECONDS, false));

                    try {
                        connection.open("shell:id");
                        fail("Expected ConnectException");
                    } catch (ConnectException expected) {
                        // Expected rejection from peer.
                    }

                    return serverFuture.get(5, TimeUnit.SECONDS);
                } finally {
                    connection.close();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static int payloadChecksum(byte[] payload) {
        int checksum = 0;
        for (byte b : payload) {
            checksum += b >= 0 ? b : b + 256;
        }
        return checksum;
    }

    private static void writePacket(OutputStream out, int command, int arg0, int arg1,
                                    byte[] payload, Integer checksumOverride) throws IOException {
        byte[] packet = AdbProtocol.generateMessage(command, arg0, arg1, payload);
        if (checksumOverride != null) {
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(16, checksumOverride);
        }
        out.write(packet);
        out.flush();
    }
}
