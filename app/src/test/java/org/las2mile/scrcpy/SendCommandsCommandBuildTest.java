package org.las2mile.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SendCommandsCommandBuildTest {

    @Test
    public void buildServerCommand_withoutAmlogic_containsCoreFlagsAndOmitsDefaults() {
        String cmd = SendCommands.buildServerCommand(2_048_000, 1920, 1280, 720, false, 0x1234ABCD);

        assertTrue(cmd.contains("CLASSPATH=/data/local/tmp/scrcpy-server.jar"));
        assertTrue(cmd.contains("com.genymobile.scrcpy.Server 3.3.4"));
        assertTrue(cmd.contains(" scid=1234abcd"));
        assertTrue(cmd.contains(" log_level=info"));
        assertTrue(cmd.contains(" tunnel_forward=true"));
        assertTrue(cmd.contains(" video_bit_rate=2048000"));
        assertTrue(cmd.contains(" max_size=1920"));
        assertFalse(cmd.contains(" video=true"));
        assertFalse(cmd.contains(" audio=true"));
        assertFalse(cmd.contains(" control=true"));
        assertFalse(cmd.contains(" cleanup=false"));
        assertFalse(cmd.contains(" video_source=display"));
        assertFalse(cmd.contains(" max_fps="));
        assertFalse(cmd.contains(" video_codec_options="));

        assertFalse(cmd.contains(" amlogic_v4l2=true"));
        assertFalse(cmd.contains(" amlogic_v4l2_width="));
    }

    @Test
    public void buildServerCommand_withAmlogic_containsAmlogicOptions() {
        String cmd = SendCommands.buildServerCommand(6_144_000, 1600, 1920, 1080, true, 0x0F0F0F0F);

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
    public void buildServerCommand_masksNegativeScid() {
        String cmd = SendCommands.buildServerCommand(1_024_000, 1280, 1280, 720, false, -1);
        assertTrue(cmd.contains(" scid=7fffffff"));
    }

    @Test
    public void buildServerCommand_omitsBitrateAndMaxSizeWhenNotPositive() {
        String cmd = SendCommands.buildServerCommand(0, 0, 1280, 720, false, 7);
        assertFalse(cmd.contains(" video_bit_rate="));
        assertFalse(cmd.contains(" max_size="));
    }
}
