package org.las2mile.scrcpy;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ScrcpyProtocolEncodingTest {

    @Test
    public void getClippedUtf8_doesNotSplitMultibyteCharacters() throws Exception {
        String text = "ab\u6d4b";

        byte[] clipped4 = invokeGetClippedUtf8(text, 4);
        byte[] clipped5 = invokeGetClippedUtf8(text, 5);

        assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), clipped4);
        assertArrayEquals("ab\u6d4b".getBytes(StandardCharsets.UTF_8), clipped5);
    }

    @Test
    public void buildSetClipboardControlMessage_encodesExpectedLayout() throws Exception {
        long sequence = 0x0102030405060708L;
        byte[] text = "hello".getBytes(StandardCharsets.UTF_8);

        byte[] msg = invokeBuildSetClipboardControlMessage(sequence, "hello", true);

        assertEquals(14 + text.length, msg.length);
        assertEquals(9, msg[0] & 0xFF);
        assertEquals(sequence, readLongBE(msg, 1));
        assertEquals(1, msg[9] & 0xFF);
        assertEquals(text.length, readIntBE(msg, 10));
        assertArrayEquals(text, Arrays.copyOfRange(msg, 14, msg.length));
    }

    @Test
    public void buildExecShellControlMessage_clipsToProtocolMaxBytes() throws Exception {
        int maxBytes = getPrivateStaticInt("CONTROL_EXEC_SHELL_TEXT_MAX_LENGTH");
        String command = repeat("a", maxBytes + 30);

        byte[] msg = invokeBuildExecShellControlMessage(42L, command);

        assertEquals(13 + maxBytes, msg.length);
        assertEquals(18, msg[0] & 0xFF);
        assertEquals(42L, readLongBE(msg, 1));
        assertEquals(maxBytes, readIntBE(msg, 9));
    }

    @Test
    public void floatToU16FixedPoint_clampsIntoValidRange() throws Exception {
        assertEquals(0, invokeFloatToU16FixedPoint(-1.0f));
        assertEquals(0, invokeFloatToU16FixedPoint(0.0f));
        assertEquals(32768, invokeFloatToU16FixedPoint(0.5f));
        assertEquals(65535, invokeFloatToU16FixedPoint(1.0f));
        assertEquals(65535, invokeFloatToU16FixedPoint(2.0f));
    }

    private static byte[] invokeGetClippedUtf8(String text, int maxBytes) throws Exception {
        Method method = Scrcpy.class.getDeclaredMethod("getClippedUtf8", String.class, int.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, text, maxBytes);
    }

    private static byte[] invokeBuildSetClipboardControlMessage(long sequence, String text, boolean paste) throws Exception {
        Method method = Scrcpy.class.getDeclaredMethod("buildSetClipboardControlMessage", long.class, String.class, boolean.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, sequence, text, paste);
    }

    private static byte[] invokeBuildExecShellControlMessage(long sequence, String command) throws Exception {
        Method method = Scrcpy.class.getDeclaredMethod("buildExecShellControlMessage", long.class, String.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, sequence, command);
    }

    private static int invokeFloatToU16FixedPoint(float value) throws Exception {
        Method method = Scrcpy.class.getDeclaredMethod("floatToU16FixedPoint", float.class);
        method.setAccessible(true);
        return (Integer) method.invoke(null, value);
    }

    private static int getPrivateStaticInt(String fieldName) throws Exception {
        Field field = Scrcpy.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static long readLongBE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 56)
                | ((long) (data[offset + 1] & 0xFF) << 48)
                | ((long) (data[offset + 2] & 0xFF) << 40)
                | ((long) (data[offset + 3] & 0xFF) << 32)
                | ((long) (data[offset + 4] & 0xFF) << 24)
                | ((long) (data[offset + 5] & 0xFF) << 16)
                | ((long) (data[offset + 6] & 0xFF) << 8)
                | ((long) (data[offset + 7] & 0xFF));
    }

    private static String repeat(String text, int count) {
        StringBuilder builder = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(text);
        }
        return builder.toString();
    }
}
