package com.genymobile.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OptionsAmlogicParseTest {

    @Test
    public void parseAmlogicWithoutExplicitCrop_doesNotForceCropToOutputSize() {
        Options options = Options.parse(
                BuildConfig.VERSION_NAME,
                "video=true",
                "audio=false",
                "video_source=display",
                "max_size=1280",
                "amlogic_v4l2=true",
                "amlogic_v4l2_width=1366",
                "amlogic_v4l2_height=768");

        assertEquals(1366, options.getAmlogicV4l2Width());
        assertEquals(768, options.getAmlogicV4l2Height());
        assertEquals(0, options.getAmlogicV4l2CropLeft());
        assertEquals(0, options.getAmlogicV4l2CropTop());
        assertEquals(0, options.getAmlogicV4l2CropWidth());
        assertEquals(0, options.getAmlogicV4l2CropHeight());
    }

    @Test
    public void parseAmlogicWithEmptyCropOption_keepsCropDisabled() {
        Options options = Options.parse(
                BuildConfig.VERSION_NAME,
                "video=true",
                "audio=false",
                "video_source=display",
                "amlogic_v4l2=true",
                "amlogic_v4l2_width=1920",
                "amlogic_v4l2_height=1080",
                "amlogic_v4l2_crop=");

        assertEquals(0, options.getAmlogicV4l2CropLeft());
        assertEquals(0, options.getAmlogicV4l2CropTop());
        assertEquals(0, options.getAmlogicV4l2CropWidth());
        assertEquals(0, options.getAmlogicV4l2CropHeight());
    }

    @Test
    public void parseAmlogicWithMaxSizeInference_doesNotForceCrop() {
        Options options = Options.parse(
                BuildConfig.VERSION_NAME,
                "video=true",
                "audio=false",
                "video_source=display",
                "amlogic_v4l2=true",
                "max_size=1280");

        assertEquals(1280, options.getAmlogicV4l2Width());
        assertEquals(720, options.getAmlogicV4l2Height());
        assertEquals(0, options.getAmlogicV4l2CropLeft());
        assertEquals(0, options.getAmlogicV4l2CropTop());
        assertEquals(0, options.getAmlogicV4l2CropWidth());
        assertEquals(0, options.getAmlogicV4l2CropHeight());
    }
}
