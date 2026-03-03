package org.las2mile.scrcpy;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SendCommandsCommandBuildTest {

    @Test
    public void buildServerStartCommand_withoutAmlogic_containsCoreFlags() throws Exception {
        String cmd = invokeBuildServerStartCommand(2_048_000, 1920, 1280, 720, false, 0x1234ABCD);

        assertTrue(cmd.contains("CLASSPATH=/data/local/tmp/scrcpy-server.jar"));
        assertTrue(cmd.contains("com.genymobile.scrcpy.Server 3.3.4"));
        assertTrue(cmd.contains(" scid=1234abcd"));
        assertTrue(cmd.contains(" video=true"));
        assertTrue(cmd.contains(" audio=true"));
        assertTrue(cmd.contains(" tunnel_forward=true"));
        assertTrue(cmd.contains(" video_bit_rate=2048000"));
        assertTrue(cmd.contains(" max_size=1920"));
        assertTrue(cmd.contains(" cleanup=false"));
        assertTrue(cmd.startsWith("("));
        assertTrue(cmd.endsWith(") >/dev/null 2>&1 < /dev/null &"));
        assertFalse(cmd.contains(";"));

        assertFalse(cmd.contains(" amlogic_v4l2=true"));
        assertFalse(cmd.contains(" amlogic_v4l2_width="));
    }

    @Test
    public void buildServerStartCommand_withAmlogic_containsAmlogicOptions() throws Exception {
        String cmd = invokeBuildServerStartCommand(6_144_000, 1600, 1920, 1080, true, 0x0F0F0F0F);

        assertTrue(cmd.contains(" scid=0f0f0f0f"));
        assertTrue(cmd.contains(" amlogic_v4l2=true"));
        assertTrue(cmd.contains(" amlogic_v4l2_instance=1"));
        assertTrue(cmd.contains(" amlogic_v4l2_source_type=1"));
        assertTrue(cmd.contains(" amlogic_v4l2_width=1920"));
        assertTrue(cmd.contains(" amlogic_v4l2_height=1080"));
        assertTrue(cmd.contains(" amlogic_v4l2_fps=30"));
        assertTrue(cmd.contains(" amlogic_v4l2_reqbufs=4"));
        assertTrue(cmd.contains(" amlogic_v4l2_format=nv21"));
    }

    @Test
    public void buildServerStartCommand_masksNegativeScid() throws Exception {
        String cmd = invokeBuildServerStartCommand(1_024_000, 1280, 1280, 720, false, -1);
        assertTrue(cmd.contains(" scid=7fffffff"));
    }

    @Test
    public void buildAppendBase64LineCommand_buildsSafePrintfCommand() throws Exception {
        String command = invokeBuildAppendBase64LineCommand("abc+/=", "serverBase64");
        assertEquals(" printf '%s\\n' 'abc+/=' >> serverBase64\n", command);
    }

    @Test
    public void buildAppendBase64LineCommand_rejectsSingleQuote() throws Exception {
        try {
            invokeBuildAppendBase64LineCommand("ab'cd", "serverBase64");
            fail("Expected IllegalArgumentException");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    private static String invokeBuildServerStartCommand(
            int bitrate,
            int maxSize,
            int width,
            int height,
            boolean useAmlogicMode,
            int scid) throws Exception {
        Method method = SendCommands.class.getDeclaredMethod(
                "buildServerStartCommand",
                int.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, bitrate, maxSize, width, height, useAmlogicMode, scid);
    }

    private static String invokeBuildAppendBase64LineCommand(String chunk, String targetFile) throws Exception {
        Method method = SendCommands.class.getDeclaredMethod(
                "buildAppendBase64LineCommand",
                String.class,
                String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, chunk, targetFile);
    }
}
