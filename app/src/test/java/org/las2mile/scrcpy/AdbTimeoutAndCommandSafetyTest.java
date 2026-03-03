package org.las2mile.scrcpy;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AdbTimeoutAndCommandSafetyTest {

    @Test
    public void scrcpyAdbTimeouts_alignWithSendCommandsPolicy() throws Exception {
        int scrcpyConnectTimeout = getPrivateStaticInt(Scrcpy.class, "ADB_CONNECT_TIMEOUT_MS");
        int scrcpySocketTimeout = getPrivateStaticInt(Scrcpy.class, "ADB_SOCKET_TIMEOUT_MS");
        int sendCommandsConnectTimeout = getPrivateStaticInt(SendCommands.class, "CONNECT_TIMEOUT_MS");
        int sendCommandsSocketTimeout = getPrivateStaticInt(SendCommands.class, "SOCKET_TIMEOUT_MS");

        assertEquals(sendCommandsConnectTimeout, scrcpyConnectTimeout);
        assertEquals(sendCommandsSocketTimeout, scrcpySocketTimeout);
        assertTrue(scrcpyConnectTimeout > 0);
        assertTrue(scrcpySocketTimeout > 0);
    }

    @Test
    public void apkViewerPromptTimeout_matchesSocketTimeout() throws Exception {
        int socketTimeoutMs = getPrivateStaticInt(ApkViewerAgentClient.class, "SOCKET_TIMEOUT_MS");
        long promptTimeoutMs = getPrivateStaticLong(ApkViewerAgentClient.class, "PROMPT_TIMEOUT_MS");

        assertEquals((long) socketTimeoutMs, promptTimeoutMs);
        assertTrue(socketTimeoutMs > 0);
    }

    @Test
    public void apkViewerBuildAppendBase64LineCommand_usesPrintfAndQuotedPayload() throws Exception {
        String command = invokeBuildAppendBase64LineCommand("abc+/=", "apkviewerAgentBase64");
        assertEquals("printf '%s\\n' 'abc+/=' >> apkviewerAgentBase64\n", command);
        assertFalse(command.contains(" echo "));
    }

    @Test
    public void apkViewerBuildAppendBase64LineCommand_rejectsSingleQuote() throws Exception {
        try {
            invokeBuildAppendBase64LineCommand("ab'cd", "apkviewerAgentBase64");
            fail("Expected IllegalArgumentException");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void proximityPowerToggle_disabledByDefault() throws Exception {
        Field field = PlayerActivity.class.getDeclaredField("ENABLE_PROXIMITY_POWER_TOGGLE");
        field.setAccessible(true);
        assertFalse(field.getBoolean(null));
    }

    private static int getPrivateStaticInt(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static long getPrivateStaticLong(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getLong(null);
    }

    private static String invokeBuildAppendBase64LineCommand(String chunk, String targetFile) throws Exception {
        Method method = ApkViewerAgentClient.class.getDeclaredMethod(
                "buildAppendBase64LineCommand",
                String.class,
                String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, chunk, targetFile);
    }
}
