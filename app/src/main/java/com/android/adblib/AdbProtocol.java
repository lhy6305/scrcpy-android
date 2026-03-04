package com.android.adblib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class AdbProtocol {
    public static final int ADB_HEADER_LENGTH = 24;

    public static final int MAX_PAYLOAD_V1 = 4 * 1024;
    public static final int MAX_PAYLOAD = 1024 * 1024;

    public static final int CMD_SYNC = 0x434e5953;
    public static final int CMD_CNXN = 0x4e584e43;
    public static final int CONNECT_VERSION_MIN = 0x01000000;
    public static final int CONNECT_VERSION_SKIP_CHECKSUM = 0x01000001;
    public static final int CONNECT_VERSION = 0x01000001;
    public static final int CONNECT_MAXDATA = MAX_PAYLOAD;
    public static final byte[] CONNECT_PAYLOAD = "host::\0".getBytes(StandardCharsets.UTF_8);

    public static final int CMD_AUTH = 0x48545541;
    public static final int AUTH_TYPE_TOKEN = 1;
    public static final int AUTH_TYPE_SIGNATURE = 2;
    public static final int AUTH_TYPE_RSA_PUBLIC = 3;

    public static final int CMD_OPEN = 0x4e45504f;
    public static final int CMD_OKAY = 0x59414b4f;
    public static final int CMD_CLSE = 0x45534c43;
    public static final int CMD_WRTE = 0x45545257;

    private static int getPayloadChecksum(byte[] payload) {
        int checksum = 0;
        for (byte b : payload) {
            checksum += b >= 0 ? b : b + 256;
        }
        return checksum;
    }

    public static boolean validateMessage(AdbMessage msg, int maxPayload) {
        if (msg.command != (msg.magic ^ -1)) {
            return false;
        }
        if (msg.payloadLength < 0 || msg.payloadLength > maxPayload) {
            return false;
        }
        return true;
    }

    public static boolean validateMessage(AdbMessage msg) {
        return validateMessage(msg, MAX_PAYLOAD);
    }

    public static byte[] generateMessage(int command, int arg0, int arg1, byte[] payload) {
        ByteBuffer buffer;
        if (payload != null) {
            buffer = ByteBuffer.allocate(ADB_HEADER_LENGTH + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        } else {
            buffer = ByteBuffer.allocate(ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        }

        buffer.putInt(command);
        buffer.putInt(arg0);
        buffer.putInt(arg1);
        if (payload != null) {
            buffer.putInt(payload.length);
            buffer.putInt(getPayloadChecksum(payload));
        } else {
            buffer.putInt(0);
            buffer.putInt(0);
        }
        buffer.putInt(command ^ -1);
        if (payload != null) {
            buffer.put(payload);
        }
        return buffer.array();
    }

    public static byte[] generateConnect() {
        return generateMessage(CMD_CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD);
    }

    public static byte[] generateAuth(int authType, byte[] data) {
        return generateMessage(CMD_AUTH, authType, 0, data);
    }

    public static byte[] generateOpen(int localId, String destination) {
        byte[] destinationBytes = destination.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(destinationBytes.length + 1);
        buffer.put(destinationBytes);
        buffer.put((byte) 0);
        return generateMessage(CMD_OPEN, localId, 0, buffer.array());
    }

    public static byte[] generateWrite(int localId, int remoteId, byte[] data) {
        return generateMessage(CMD_WRTE, localId, remoteId, data);
    }

    public static byte[] generateClose(int localId, int remoteId) {
        return generateMessage(CMD_CLSE, localId, remoteId, null);
    }

    public static byte[] generateReady(int localId, int remoteId) {
        return generateMessage(CMD_OKAY, localId, remoteId, null);
    }

    static final class AdbMessage {
        public int command;
        public int arg0;
        public int arg1;
        public int payloadLength;
        public int checksum;
        public int magic;
        public byte[] payload;

        static AdbMessage parseAdbMessage(InputStream inputStream, int maxPayload) throws IOException {
            AdbMessage msg = new AdbMessage();
            ByteBuffer header = ByteBuffer.allocate(ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
            int offset = 0;
            while (offset < ADB_HEADER_LENGTH) {
                int count = inputStream.read(header.array(), offset, ADB_HEADER_LENGTH - offset);
                if (count < 0) {
                    throw new IOException("Stream closed");
                }
                offset += count;
            }

            msg.command = header.getInt();
            msg.arg0 = header.getInt();
            msg.arg1 = header.getInt();
            msg.payloadLength = header.getInt();
            msg.checksum = header.getInt();
            msg.magic = header.getInt();

            if (msg.payloadLength < 0 || msg.payloadLength > maxPayload) {
                throw new IOException("Invalid payload length: " + msg.payloadLength);
            }

            msg.payload = new byte[msg.payloadLength];
            offset = 0;
            while (offset < msg.payloadLength) {
                int count = inputStream.read(msg.payload, offset, msg.payloadLength - offset);
                if (count < 0) {
                    throw new IOException("Stream closed");
                }
                offset += count;
            }
            return msg;
        }

        static AdbMessage parseAdbMessage(InputStream inputStream) throws IOException {
            return parseAdbMessage(inputStream, MAX_PAYLOAD);
        }
    }
}
