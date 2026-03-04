package org.las2mile.scrcpy;

import com.android.adblib.AdbConnection;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void scrcpyStreamOpenRetryWindow_matchesOfficialBaseline() throws Exception {
        int retries = getPrivateStaticInt(Scrcpy.class, "STREAM_OPEN_MAX_RETRIES");
        long delayMs = getPrivateStaticLong(Scrcpy.class, "STREAM_OPEN_RETRY_DELAY_MS");

        assertEquals(100, retries);
        assertEquals(100L, delayMs);
        assertEquals(10_000L, retries * delayMs);
    }

    @Test
    public void apkViewerPromptTimeout_matchesSocketTimeout() throws Exception {
        int socketTimeoutMs = getPrivateStaticInt(ApkViewerAgentClient.class, "SOCKET_TIMEOUT_MS");
        long promptTimeoutMs = getPrivateStaticLong(ApkViewerAgentClient.class, "PROMPT_TIMEOUT_MS");

        assertEquals((long) socketTimeoutMs, promptTimeoutMs);
        assertTrue(socketTimeoutMs > 0);
    }

    @Test
    public void apkViewerDeploy_rejectsEmptyJarBytesBeforeNetwork() {
        try {
            ApkViewerAgentClient.deploy(null, null, new byte[0], null);
        } catch (IOException e) {
            assertEquals("agentJarBytes is empty", e.getMessage());
            return;
        } catch (InterruptedException e) {
            throw new AssertionError("Unexpected interruption", e);
        }
        throw new AssertionError("Expected IOException");
    }

    @Test
    public void apkViewerDeployWithAdb_rejectsNullConnection() {
        try {
            ApkViewerAgentClient.deploy((AdbConnection) null, new byte[]{1}, null);
        } catch (IOException e) {
            assertEquals("adb is null", e.getMessage());
            return;
        } catch (InterruptedException e) {
            throw new AssertionError("Unexpected interruption", e);
        }
        throw new AssertionError("Expected IOException");
    }

    @Test
    public void apkViewerExecWithAdb_rejectsNullConnection() {
        try {
            ApkViewerAgentClient.exec((AdbConnection) null, "echo test");
        } catch (IOException e) {
            assertEquals("adb is null", e.getMessage());
            return;
        } catch (InterruptedException e) {
            throw new AssertionError("Unexpected interruption", e);
        }
        throw new AssertionError("Expected IOException");
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
}
