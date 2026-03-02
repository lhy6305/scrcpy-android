package com.genymobile.scrcpy;

import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.audio.AudioSource;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.NewDisplay;
import com.genymobile.scrcpy.device.Orientation;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.CodecOption;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.CameraAspectRatio;
import com.genymobile.scrcpy.video.CameraFacing;
import com.genymobile.scrcpy.video.VideoCodec;
import com.genymobile.scrcpy.video.VideoSource;
import com.genymobile.scrcpy.wrappers.WindowManager;

import android.graphics.Rect;
import android.util.Pair;

import java.util.List;
import java.util.Locale;

public class Options {

    private static final int AML_V4L2_FALLBACK_WIDTH = 640;
    private static final int AML_V4L2_FALLBACK_HEIGHT = 480;
    private static final int AML_V4L2_PIXEL_FORMAT_INVALID = -1;
    private static final int AML_V4L2_FMT_NV21 = 0x3132564E;
    private static final int AML_V4L2_FMT_NV12 = 0x3231564E;
    private static final int AML_V4L2_FMT_YUYV = 0x56595559;
    private static final int AML_V4L2_FMT_YV12 = 0x32315659;
    private static final int AML_V4L2_FMT_RGB3 = 0x33424752;
    private static final int AML_V4L2_FMT_RGB4 = 0x34424752;
    private static final int AML_V4L2_FMT_RGBP = 0x50424752;
    private static final int AML_V4L2_FMT_RGBR = 0x52424752;
    private static final String AML_V4L2_VIDEO_ENCODER = "amlogic_v4l2";

    private Ln.Level logLevel = Ln.Level.DEBUG;
    private int scid = -1; // 31-bit non-negative value, or -1
    private boolean video = true;
    private boolean audio = true;
    private int maxSize;
    private VideoCodec videoCodec = VideoCodec.H264;
    private AudioCodec audioCodec = AudioCodec.OPUS;
    private VideoSource videoSource = VideoSource.DISPLAY;
    private AudioSource audioSource = AudioSource.OUTPUT;
    private boolean audioDup;
    private int videoBitRate = 8000000;
    private int audioBitRate = 128000;
    private float maxFps;
    private float angle;
    private boolean tunnelForward;
    private Rect crop;
    private boolean control = true;
    private int displayId;
    private String cameraId;
    private Size cameraSize;
    private CameraFacing cameraFacing;
    private CameraAspectRatio cameraAspectRatio;
    private int cameraFps;
    private boolean cameraHighSpeed;
    private boolean showTouches;
    private boolean stayAwake;
    private int screenOffTimeout = -1;
    private int displayImePolicy = -1;
    private List<CodecOption> videoCodecOptions;
    private List<CodecOption> audioCodecOptions;
    private boolean amlogicV4l2;
    private String amlogicV4l2Device = "/dev/video12";
    private boolean amlogicV4l2DeviceSet;
    private int amlogicV4l2Instance = 1;
    private int amlogicV4l2SourceType = 1;
    private int amlogicV4l2Width = 1280;
    private int amlogicV4l2Height = 720;
    private int amlogicV4l2Fps = 30;
    private int amlogicV4l2PortType = -1; // auto resolve from source_type + ro.vendor.screencontrol.porttype
    private int amlogicV4l2Mode = -1;
    private int amlogicV4l2Rotation = -1;
    private int amlogicV4l2CropLeft;
    private int amlogicV4l2CropTop;
    private int amlogicV4l2CropWidth = 1280;
    private int amlogicV4l2CropHeight = 720;
    private int amlogicV4l2ReqBufCount = 4;
    private int amlogicV4l2PixelFormat = AML_V4L2_FMT_NV21; // NV21
    private boolean amlogicV4l2CropSet;

    private String videoEncoder;
    private String audioEncoder;
    private boolean powerOffScreenOnClose;
    private boolean clipboardAutosync = true;
    private boolean downsizeOnError = true;
    private boolean cleanup = true;
    private boolean powerOn = true;

    private NewDisplay newDisplay;
    private boolean vdDestroyContent = true;
    private boolean vdSystemDecorations = true;

    private Orientation.Lock captureOrientationLock = Orientation.Lock.Unlocked;
    private Orientation captureOrientation = Orientation.Orient0;

    private boolean listEncoders;
    private boolean listDisplays;
    private boolean listCameras;
    private boolean listCameraSizes;
    private boolean listApps;

    // Options not used by the scrcpy client, but useful to use scrcpy-server directly
    private boolean sendDeviceMeta = true; // send device name and size
    private boolean sendFrameMeta = true; // send PTS so that the client may record properly
    private boolean sendDummyByte = true; // write a byte on start to detect connection issues
    private boolean sendCodecMeta = true; // write the codec metadata before the stream

    public Ln.Level getLogLevel() {
        return logLevel;
    }

    public int getScid() {
        return scid;
    }

    public boolean getVideo() {
        return video;
    }

    public boolean getAudio() {
        return audio;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public VideoCodec getVideoCodec() {
        return videoCodec;
    }

    public AudioCodec getAudioCodec() {
        return audioCodec;
    }

    public VideoSource getVideoSource() {
        return videoSource;
    }

    public AudioSource getAudioSource() {
        return audioSource;
    }

    public boolean getAudioDup() {
        return audioDup;
    }

    public int getVideoBitRate() {
        return videoBitRate;
    }

    public int getAudioBitRate() {
        return audioBitRate;
    }

    public float getMaxFps() {
        return maxFps;
    }

    public float getAngle() {
        return angle;
    }

    public boolean isTunnelForward() {
        return tunnelForward;
    }

    public Rect getCrop() {
        return crop;
    }

    public boolean getControl() {
        return control;
    }

    public int getDisplayId() {
        return displayId;
    }

    public String getCameraId() {
        return cameraId;
    }

    public Size getCameraSize() {
        return cameraSize;
    }

    public CameraFacing getCameraFacing() {
        return cameraFacing;
    }

    public CameraAspectRatio getCameraAspectRatio() {
        return cameraAspectRatio;
    }

    public int getCameraFps() {
        return cameraFps;
    }

    public boolean getCameraHighSpeed() {
        return cameraHighSpeed;
    }

    public boolean getShowTouches() {
        return showTouches;
    }

    public boolean getStayAwake() {
        return stayAwake;
    }

    public int getScreenOffTimeout() {
        return screenOffTimeout;
    }

    public int getDisplayImePolicy() {
        return displayImePolicy;
    }

    public List<CodecOption> getVideoCodecOptions() {
        return videoCodecOptions;
    }

    public List<CodecOption> getAudioCodecOptions() {
        return audioCodecOptions;
    }

    public String getVideoEncoder() {
        return videoEncoder;
    }

    public boolean getAmlogicV4l2() {
        return amlogicV4l2;
    }

    public String getAmlogicV4l2Device() {
        return amlogicV4l2Device;
    }

    public boolean getAmlogicV4l2DeviceSet() {
        return amlogicV4l2DeviceSet;
    }

    public int getAmlogicV4l2Instance() {
        return amlogicV4l2Instance;
    }

    public int getAmlogicV4l2SourceType() {
        return amlogicV4l2SourceType;
    }

    public int getAmlogicV4l2Width() {
        return amlogicV4l2Width;
    }

    public int getAmlogicV4l2Height() {
        return amlogicV4l2Height;
    }

    public int getAmlogicV4l2Fps() {
        return amlogicV4l2Fps;
    }

    public int getAmlogicV4l2PortType() {
        return amlogicV4l2PortType;
    }

    public int getAmlogicV4l2Mode() {
        return amlogicV4l2Mode;
    }

    public int getAmlogicV4l2Rotation() {
        return amlogicV4l2Rotation;
    }

    public int getAmlogicV4l2CropLeft() {
        return amlogicV4l2CropLeft;
    }

    public int getAmlogicV4l2CropTop() {
        return amlogicV4l2CropTop;
    }

    public int getAmlogicV4l2CropWidth() {
        return amlogicV4l2CropWidth;
    }

    public int getAmlogicV4l2CropHeight() {
        return amlogicV4l2CropHeight;
    }

    public int getAmlogicV4l2ReqBufCount() {
        return amlogicV4l2ReqBufCount;
    }

    public int getAmlogicV4l2PixelFormat() {
        return amlogicV4l2PixelFormat;
    }

    public String getAudioEncoder() {
        return audioEncoder;
    }

    public boolean getPowerOffScreenOnClose() {
        return this.powerOffScreenOnClose;
    }

    public boolean getClipboardAutosync() {
        return clipboardAutosync;
    }

    public boolean getDownsizeOnError() {
        return downsizeOnError;
    }

    public boolean getCleanup() {
        return cleanup;
    }

    public boolean getPowerOn() {
        return powerOn;
    }

    public NewDisplay getNewDisplay() {
        return newDisplay;
    }

    public Orientation getCaptureOrientation() {
        return captureOrientation;
    }

    public Orientation.Lock getCaptureOrientationLock() {
        return captureOrientationLock;
    }

    public boolean getVDDestroyContent() {
        return vdDestroyContent;
    }

    public boolean getVDSystemDecorations() {
        return vdSystemDecorations;
    }

    public boolean getList() {
        return listEncoders || listDisplays || listCameras || listCameraSizes || listApps;
    }

    public boolean getListEncoders() {
        return listEncoders;
    }

    public boolean getListDisplays() {
        return listDisplays;
    }

    public boolean getListCameras() {
        return listCameras;
    }

    public boolean getListCameraSizes() {
        return listCameraSizes;
    }

    public boolean getListApps() {
        return listApps;
    }

    public boolean getSendDeviceMeta() {
        return sendDeviceMeta;
    }

    public boolean getSendFrameMeta() {
        return sendFrameMeta;
    }

    public boolean getSendDummyByte() {
        return sendDummyByte;
    }

    public boolean getSendCodecMeta() {
        return sendCodecMeta;
    }

    @SuppressWarnings("MethodLength")
    public static Options parse(String... args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Missing client version");
        }

        String clientVersion = args[0];
        if (!clientVersion.equals(BuildConfig.VERSION_NAME)) {
            throw new IllegalArgumentException(
                    "The server version (" + BuildConfig.VERSION_NAME + ") does not match the client " + "(" + clientVersion + ")");
        }

        Options options = new Options();
        boolean amlogicSwitchExplicit = false;
        boolean amlogicRequestedByVideoEncoder = false;
        boolean amlogicWidthExplicit = false;
        boolean amlogicHeightExplicit = false;
        boolean amlogicFpsExplicit = false;
        boolean amlogicCropExplicit = false;

        for (int i = 1; i < args.length; ++i) {
            String arg = args[i];
            int equalIndex = arg.indexOf('=');
            if (equalIndex == -1) {
                throw new IllegalArgumentException("Invalid key=value pair: \"" + arg + "\"");
            }
            String key = arg.substring(0, equalIndex);
            String value = arg.substring(equalIndex + 1);
            switch (key) {
                case "scid":
                    int scid = Integer.parseInt(value, 0x10);
                    if (scid < -1) {
                        throw new IllegalArgumentException("scid may not be negative (except -1 for 'none'): " + scid);
                    }
                    options.scid = scid;
                    break;
                case "log_level":
                    options.logLevel = Ln.Level.valueOf(value.toUpperCase(Locale.ENGLISH));
                    break;
                case "video":
                    options.video = Boolean.parseBoolean(value);
                    break;
                case "audio":
                    options.audio = Boolean.parseBoolean(value);
                    break;
                case "video_codec":
                    VideoCodec videoCodec = VideoCodec.findByName(value);
                    if (videoCodec == null) {
                        throw new IllegalArgumentException("Video codec " + value + " not supported");
                    }
                    options.videoCodec = videoCodec;
                    break;
                case "audio_codec":
                    AudioCodec audioCodec = AudioCodec.findByName(value);
                    if (audioCodec == null) {
                        throw new IllegalArgumentException("Audio codec " + value + " not supported");
                    }
                    options.audioCodec = audioCodec;
                    break;
                case "video_source":
                    VideoSource videoSource = VideoSource.findByName(value);
                    if (videoSource == null) {
                        throw new IllegalArgumentException("Video source " + value + " not supported");
                    }
                    options.videoSource = videoSource;
                    break;
                case "audio_source":
                    AudioSource audioSource = AudioSource.findByName(value);
                    if (audioSource == null) {
                        throw new IllegalArgumentException("Audio source " + value + " not supported");
                    }
                    options.audioSource = audioSource;
                    break;
                case "audio_dup":
                    options.audioDup = Boolean.parseBoolean(value);
                    break;
                case "max_size":
                    options.maxSize = Integer.parseInt(value) & ~7; // multiple of 8
                    break;
                case "video_bit_rate":
                    options.videoBitRate = Integer.parseInt(value);
                    break;
                case "audio_bit_rate":
                    options.audioBitRate = Integer.parseInt(value);
                    break;
                case "max_fps":
                    options.maxFps = parseFloat("max_fps", value);
                    break;
                case "angle":
                    options.angle = parseFloat("angle", value);
                    break;
                case "tunnel_forward":
                    options.tunnelForward = Boolean.parseBoolean(value);
                    break;
                case "crop":
                    if (!value.isEmpty()) {
                        options.crop = parseCrop(value);
                    }
                    break;
                case "control":
                    options.control = Boolean.parseBoolean(value);
                    break;
                case "display_id":
                    options.displayId = Integer.parseInt(value);
                    break;
                case "show_touches":
                    options.showTouches = Boolean.parseBoolean(value);
                    break;
                case "stay_awake":
                    options.stayAwake = Boolean.parseBoolean(value);
                    break;
                case "screen_off_timeout":
                    options.screenOffTimeout = Integer.parseInt(value);
                    if (options.screenOffTimeout < -1) {
                        throw new IllegalArgumentException("Invalid screen off timeout: " + options.screenOffTimeout);
                    }
                    break;
                case "video_codec_options":
                    options.videoCodecOptions = CodecOption.parse(value);
                    break;
                case "audio_codec_options":
                    options.audioCodecOptions = CodecOption.parse(value);
                    break;
                case "video_encoder":
                    if (!value.isEmpty()) {
                        options.videoEncoder = value;
                        AmlogicVideoEncoderConfig amlogicVideoEncoderConfig = parseAmlogicVideoEncoderConfig(value);
                        if (amlogicVideoEncoderConfig != null) {
                            amlogicRequestedByVideoEncoder = true;
                            if (amlogicVideoEncoderConfig.deviceSet) {
                                options.amlogicV4l2Device = amlogicVideoEncoderConfig.device;
                                options.amlogicV4l2DeviceSet = true;
                            }
                            if (amlogicVideoEncoderConfig.instanceSet) {
                                options.amlogicV4l2Instance = amlogicVideoEncoderConfig.instance;
                            }
                            if (amlogicVideoEncoderConfig.sourceTypeSet) {
                                options.amlogicV4l2SourceType = amlogicVideoEncoderConfig.sourceType;
                            }
                            if (amlogicVideoEncoderConfig.widthSet) {
                                options.amlogicV4l2Width = amlogicVideoEncoderConfig.width;
                                amlogicWidthExplicit = true;
                            }
                            if (amlogicVideoEncoderConfig.heightSet) {
                                options.amlogicV4l2Height = amlogicVideoEncoderConfig.height;
                                amlogicHeightExplicit = true;
                            }
                            if (amlogicVideoEncoderConfig.fpsSet) {
                                options.amlogicV4l2Fps = amlogicVideoEncoderConfig.fps;
                                amlogicFpsExplicit = true;
                            }
                            if (amlogicVideoEncoderConfig.portTypeSet) {
                                options.amlogicV4l2PortType = amlogicVideoEncoderConfig.portType;
                            }
                            if (amlogicVideoEncoderConfig.modeSet) {
                                options.amlogicV4l2Mode = amlogicVideoEncoderConfig.mode;
                            }
                            if (amlogicVideoEncoderConfig.rotationSet) {
                                options.amlogicV4l2Rotation = amlogicVideoEncoderConfig.rotation;
                            }
                            if (amlogicVideoEncoderConfig.cropSet) {
                                options.amlogicV4l2CropLeft = amlogicVideoEncoderConfig.cropLeft;
                                options.amlogicV4l2CropTop = amlogicVideoEncoderConfig.cropTop;
                                options.amlogicV4l2CropWidth = amlogicVideoEncoderConfig.cropWidth;
                                options.amlogicV4l2CropHeight = amlogicVideoEncoderConfig.cropHeight;
                                options.amlogicV4l2CropSet = true;
                                amlogicCropExplicit = true;
                            }
                            if (amlogicVideoEncoderConfig.reqBufCountSet) {
                                options.amlogicV4l2ReqBufCount = amlogicVideoEncoderConfig.reqBufCount;
                            }
                            if (amlogicVideoEncoderConfig.pixelFormatSet) {
                                options.amlogicV4l2PixelFormat = amlogicVideoEncoderConfig.pixelFormat;
                            }
                        }
                    }
                    break;
                case "amlogic_v4l2":
                    amlogicSwitchExplicit = true;
                    options.amlogicV4l2 = Boolean.parseBoolean(value);
                    break;
                case "amlogic_v4l2_device":
                    if (!value.isEmpty()) {
                        options.amlogicV4l2Device = value;
                        options.amlogicV4l2DeviceSet = true;
                    } else {
                        options.amlogicV4l2DeviceSet = false;
                    }
                    break;
                case "amlogic_v4l2_instance":
                    options.amlogicV4l2Instance = Integer.parseInt(value);
                    if (options.amlogicV4l2Instance < 0 || options.amlogicV4l2Instance > 1) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2_instance: " + options.amlogicV4l2Instance);
                    }
                    break;
                case "amlogic_v4l2_source_type":
                    options.amlogicV4l2SourceType = Integer.parseInt(value);
                    if (options.amlogicV4l2SourceType < 0) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2_source_type: " + options.amlogicV4l2SourceType);
                    }
                    break;
                case "amlogic_v4l2_width":
                    amlogicWidthExplicit = true;
                    options.amlogicV4l2Width = parsePositiveIntOrDefault("amlogic_v4l2_width", value, AML_V4L2_FALLBACK_WIDTH);
                    break;
                case "amlogic_v4l2_height":
                    amlogicHeightExplicit = true;
                    options.amlogicV4l2Height = parsePositiveIntOrDefault("amlogic_v4l2_height", value, AML_V4L2_FALLBACK_HEIGHT);
                    break;
                case "amlogic_v4l2_fps":
                    amlogicFpsExplicit = true;
                    options.amlogicV4l2Fps = Integer.parseInt(value);
                    if (options.amlogicV4l2Fps <= 0) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2_fps: " + options.amlogicV4l2Fps);
                    }
                    break;
                case "amlogic_v4l2_port":
                    options.amlogicV4l2PortType = Integer.decode(value);
                    break;
                case "amlogic_v4l2_mode":
                    options.amlogicV4l2Mode = Integer.parseInt(value);
                    break;
                case "amlogic_v4l2_rotation":
                    options.amlogicV4l2Rotation = Integer.parseInt(value);
                    if (options.amlogicV4l2Rotation != -1 && options.amlogicV4l2Rotation != 0 && options.amlogicV4l2Rotation != 90
                            && options.amlogicV4l2Rotation != 180
                            && options.amlogicV4l2Rotation != 270) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2_rotation: " + options.amlogicV4l2Rotation);
                    }
                    break;
                case "amlogic_v4l2_crop":
                    if (!value.isEmpty()) {
                        amlogicCropExplicit = true;
                        Rect amlCrop = parseAmlogicCrop(value);
                        options.amlogicV4l2CropLeft = amlCrop.left;
                        options.amlogicV4l2CropTop = amlCrop.top;
                        options.amlogicV4l2CropWidth = amlCrop.width();
                        options.amlogicV4l2CropHeight = amlCrop.height();
                        options.amlogicV4l2CropSet = true;
                    } else {
                        options.amlogicV4l2CropLeft = 0;
                        options.amlogicV4l2CropTop = 0;
                        options.amlogicV4l2CropWidth = 0;
                        options.amlogicV4l2CropHeight = 0;
                        options.amlogicV4l2CropSet = false;
                    }
                    break;
                case "amlogic_v4l2_reqbufs":
                    options.amlogicV4l2ReqBufCount = Integer.parseInt(value);
                    if (options.amlogicV4l2ReqBufCount < 2) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2_reqbufs: " + options.amlogicV4l2ReqBufCount);
                    }
                    break;
                case "amlogic_v4l2_format":
                    options.amlogicV4l2PixelFormat = parseAmlogicPixelFormat(value);
                    break;
                case "audio_encoder":
                    if (!value.isEmpty()) {
                        options.audioEncoder = value;
                    }
                case "power_off_on_close":
                    options.powerOffScreenOnClose = Boolean.parseBoolean(value);
                    break;
                case "clipboard_autosync":
                    options.clipboardAutosync = Boolean.parseBoolean(value);
                    break;
                case "downsize_on_error":
                    options.downsizeOnError = Boolean.parseBoolean(value);
                    break;
                case "cleanup":
                    options.cleanup = Boolean.parseBoolean(value);
                    break;
                case "power_on":
                    options.powerOn = Boolean.parseBoolean(value);
                    break;
                case "list_encoders":
                    options.listEncoders = Boolean.parseBoolean(value);
                    break;
                case "list_displays":
                    options.listDisplays = Boolean.parseBoolean(value);
                    break;
                case "list_cameras":
                    options.listCameras = Boolean.parseBoolean(value);
                    break;
                case "list_camera_sizes":
                    options.listCameraSizes = Boolean.parseBoolean(value);
                    break;
                case "list_apps":
                    options.listApps = Boolean.parseBoolean(value);
                    break;
                case "camera_id":
                    if (!value.isEmpty()) {
                        options.cameraId = value;
                    }
                    break;
                case "camera_size":
                    if (!value.isEmpty()) {
                        options.cameraSize = parseSize(value);
                    }
                    break;
                case "camera_facing":
                    if (!value.isEmpty()) {
                        CameraFacing facing = CameraFacing.findByName(value);
                        if (facing == null) {
                            throw new IllegalArgumentException("Camera facing " + value + " not supported");
                        }
                        options.cameraFacing = facing;
                    }
                    break;
                case "camera_ar":
                    if (!value.isEmpty()) {
                        options.cameraAspectRatio = parseCameraAspectRatio(value);
                    }
                    break;
                case "camera_fps":
                    options.cameraFps = Integer.parseInt(value);
                    break;
                case "camera_high_speed":
                    options.cameraHighSpeed = Boolean.parseBoolean(value);
                    break;
                case "new_display":
                    options.newDisplay = parseNewDisplay(value);
                    break;
                case "vd_destroy_content":
                    options.vdDestroyContent = Boolean.parseBoolean(value);
                    break;
                case "vd_system_decorations":
                    options.vdSystemDecorations = Boolean.parseBoolean(value);
                    break;
                case "capture_orientation":
                    Pair<Orientation.Lock, Orientation> pair = parseCaptureOrientation(value);
                    options.captureOrientationLock = pair.first;
                    options.captureOrientation = pair.second;
                    break;
                case "display_ime_policy":
                    options.displayImePolicy = parseDisplayImePolicy(value);
                    break;
                case "send_device_meta":
                    options.sendDeviceMeta = Boolean.parseBoolean(value);
                    break;
                case "send_frame_meta":
                    options.sendFrameMeta = Boolean.parseBoolean(value);
                    break;
                case "send_dummy_byte":
                    options.sendDummyByte = Boolean.parseBoolean(value);
                    break;
                case "send_codec_meta":
                    options.sendCodecMeta = Boolean.parseBoolean(value);
                    break;
                case "raw_stream":
                    boolean rawStream = Boolean.parseBoolean(value);
                    if (rawStream) {
                        options.sendDeviceMeta = false;
                        options.sendFrameMeta = false;
                        options.sendDummyByte = false;
                        options.sendCodecMeta = false;
                    }
                    break;
                default:
                    Ln.w("Unknown server option: " + key);
                    break;
            }
        }

        if (options.newDisplay != null) {
            assert options.displayId == 0 : "Must not set both displayId and newDisplay";
            options.displayId = Device.DISPLAY_ID_NONE;
        }

        if (amlogicRequestedByVideoEncoder && !amlogicSwitchExplicit) {
            options.amlogicV4l2 = true;
        }

        if (options.amlogicV4l2) {
            if (!amlogicCropExplicit && options.crop != null) {
                options.amlogicV4l2CropLeft = options.crop.left;
                options.amlogicV4l2CropTop = options.crop.top;
                options.amlogicV4l2CropWidth = options.crop.width();
                options.amlogicV4l2CropHeight = options.crop.height();
                options.amlogicV4l2CropSet = true;
            }

            if (options.crop != null) {
                if (!amlogicWidthExplicit) {
                    options.amlogicV4l2Width = options.crop.width();
                }
                if (!amlogicHeightExplicit) {
                    options.amlogicV4l2Height = options.crop.height();
                }
            } else if (options.maxSize > 0) {
                if (!amlogicWidthExplicit) {
                    options.amlogicV4l2Width = options.maxSize;
                }
                if (!amlogicHeightExplicit) {
                    options.amlogicV4l2Height = inferAmlogicHeightFromMaxSize(options.maxSize);
                }
            }

            if (!amlogicFpsExplicit && options.maxFps > 0) {
                options.amlogicV4l2Fps = inferAmlogicFps(options.maxFps);
            }
        }

        if (options.amlogicV4l2 && !options.amlogicV4l2CropSet) {
            options.amlogicV4l2CropLeft = 0;
            options.amlogicV4l2CropTop = 0;
            options.amlogicV4l2CropWidth = options.amlogicV4l2Width;
            options.amlogicV4l2CropHeight = options.amlogicV4l2Height;
        }

        return options;
    }

    private static final class AmlogicVideoEncoderConfig {
        private boolean deviceSet;
        private String device;
        private boolean instanceSet;
        private int instance;
        private boolean sourceTypeSet;
        private int sourceType;
        private boolean widthSet;
        private int width;
        private boolean heightSet;
        private int height;
        private boolean fpsSet;
        private int fps;
        private boolean portTypeSet;
        private int portType;
        private boolean modeSet;
        private int mode;
        private boolean rotationSet;
        private int rotation;
        private boolean cropSet;
        private int cropLeft;
        private int cropTop;
        private int cropWidth;
        private int cropHeight;
        private boolean reqBufCountSet;
        private int reqBufCount;
        private boolean pixelFormatSet;
        private int pixelFormat;
    }

    private static AmlogicVideoEncoderConfig parseAmlogicVideoEncoderConfig(String value) {
        AmlogicVideoEncoderConfig config = new AmlogicVideoEncoderConfig();
        if (value.equalsIgnoreCase(AML_V4L2_VIDEO_ENCODER)) {
            return config;
        }

        String prefix = AML_V4L2_VIDEO_ENCODER + ":";
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }

        String extras = value.substring(prefix.length());
        if (extras.isEmpty()) {
            return config;
        }

        String[] tokens = extras.split(",");
        for (String token : tokens) {
            token = token.trim();
            int equalIndex = token.indexOf('=');
            if (equalIndex <= 0 || equalIndex == token.length() - 1) {
                throw new IllegalArgumentException("Invalid amlogic video_encoder option: \"" + token + "\"");
            }
            String key = token.substring(0, equalIndex).trim().replace('-', '_');
            String optionValue = token.substring(equalIndex + 1).trim();
            String normalizedKey = key.toLowerCase(Locale.ENGLISH);
            switch (normalizedKey) {
                case "device":
                    config.device = optionValue;
                    config.deviceSet = true;
                    break;
                case "instance":
                    config.instance = Integer.parseInt(optionValue);
                    if (config.instance < 0 || config.instance > 1) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2 instance: " + config.instance);
                    }
                    config.instanceSet = true;
                    break;
                case "source_type":
                    config.sourceType = Integer.parseInt(optionValue);
                    if (config.sourceType < 0) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2 source_type: " + config.sourceType);
                    }
                    config.sourceTypeSet = true;
                    break;
                case "width":
                    config.width = parsePositiveIntOrDefault("video_encoder.width", optionValue, AML_V4L2_FALLBACK_WIDTH);
                    config.widthSet = true;
                    break;
                case "height":
                    config.height = parsePositiveIntOrDefault("video_encoder.height", optionValue, AML_V4L2_FALLBACK_HEIGHT);
                    config.heightSet = true;
                    break;
                case "fps":
                    config.fps = Integer.parseInt(optionValue);
                    if (config.fps <= 0) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2 fps: " + config.fps);
                    }
                    config.fpsSet = true;
                    break;
                case "port":
                    config.portType = Integer.decode(optionValue);
                    config.portTypeSet = true;
                    break;
                case "mode":
                    config.mode = Integer.parseInt(optionValue);
                    config.modeSet = true;
                    break;
                case "rotation":
                    config.rotation = Integer.parseInt(optionValue);
                    if (config.rotation != -1 && config.rotation != 0 && config.rotation != 90 && config.rotation != 180
                            && config.rotation != 270) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2 rotation: " + config.rotation);
                    }
                    config.rotationSet = true;
                    break;
                case "crop":
                    Rect amlCrop = parseAmlogicCrop(optionValue);
                    config.cropLeft = amlCrop.left;
                    config.cropTop = amlCrop.top;
                    config.cropWidth = amlCrop.width();
                    config.cropHeight = amlCrop.height();
                    config.cropSet = true;
                    break;
                case "reqbufs":
                    config.reqBufCount = Integer.parseInt(optionValue);
                    if (config.reqBufCount < 2) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2 reqbufs: " + config.reqBufCount);
                    }
                    config.reqBufCountSet = true;
                    break;
                case "format":
                    config.pixelFormat = parseAmlogicPixelFormat(optionValue);
                    config.pixelFormatSet = true;
                    break;
                case "sourcetype":
                    config.sourceType = Integer.parseInt(optionValue);
                    if (config.sourceType < 0) {
                        throw new IllegalArgumentException("Invalid amlogic_v4l2 source_type: " + config.sourceType);
                    }
                    config.sourceTypeSet = true;
                    break;
                case "pixelformat":
                case "pixel_format":
                    config.pixelFormat = parseAmlogicPixelFormat(optionValue);
                    config.pixelFormatSet = true;
                    break;
                default:
                    Ln.w("Ignoring unsupported amlogic video_encoder option: \"" + key + "\"");
                    break;
            }
        }

        return config;
    }

    private static int inferAmlogicHeightFromMaxSize(int maxSize) {
        int inferredHeight = (maxSize * 9 / 16) & ~1;
        return inferredHeight > 0 ? inferredHeight : maxSize;
    }

    private static int inferAmlogicFps(float maxFps) {
        int inferredFps = Math.round(maxFps);
        return inferredFps > 0 ? inferredFps : 30;
    }

    private static Rect parseCrop(String crop) {
        // input format: "width:height:x:y"
        String[] tokens = crop.split(":");
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Crop must contains 4 values separated by colons: \"" + crop + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid crop size: " + width + "x" + height);
        }
        int x = Integer.parseInt(tokens[2]);
        int y = Integer.parseInt(tokens[3]);
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("Invalid crop offset: " + x + ":" + y);
        }
        return new Rect(x, y, x + width, y + height);
    }

    private static Rect parseAmlogicCrop(String crop) {
        // input format: "left:top:width:height"
        String[] tokens = crop.split(":");
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Amlogic crop must contain 4 values: \"" + crop + "\"");
        }
        int left = Integer.parseInt(tokens[0]);
        int top = Integer.parseInt(tokens[1]);
        int width = Integer.parseInt(tokens[2]);
        int height = Integer.parseInt(tokens[3]);
        if (left < 0 || top < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid amlogic crop: \"" + crop + "\"");
        }
        return new Rect(left, top, left + width, top + height);
    }

    private static int parseAmlogicPixelFormat(String value) {
        String lower = value.toLowerCase(Locale.ENGLISH);
        switch (lower) {
            case "nv21":
                return AML_V4L2_FMT_NV21;
            case "nv12":
                return AML_V4L2_FMT_NV12;
            case "yuyv":
                return AML_V4L2_FMT_YUYV;
            case "yv12":
                return AML_V4L2_FMT_YV12;
            case "rgb3":
                return AML_V4L2_FMT_RGB3;
            case "rgb4":
                return AML_V4L2_FMT_RGB4;
            case "rgbp":
                return AML_V4L2_FMT_RGBP;
            case "rgbr":
                return AML_V4L2_FMT_RGBR;
            default:
                try {
                    int format = Integer.decode(value);
                    return sanitizeAmlogicPixelFormat(format, value);
                } catch (NumberFormatException e) {
                    Ln.w("Invalid amlogic_v4l2_format: \"" + value + "\", fallback to 640x480+nv21");
                    return AML_V4L2_PIXEL_FORMAT_INVALID;
                }
        }
    }

    private static int sanitizeAmlogicPixelFormat(int format, String originalValue) {
        switch (format) {
            case AML_V4L2_FMT_NV21:
            case AML_V4L2_FMT_NV12:
            case AML_V4L2_FMT_YUYV:
            case AML_V4L2_FMT_YV12:
            case AML_V4L2_FMT_RGB3:
            case AML_V4L2_FMT_RGB4:
            case AML_V4L2_FMT_RGBP:
            case AML_V4L2_FMT_RGBR:
                return format;
            default:
                Ln.w("Unsupported amlogic_v4l2_format: \"" + originalValue + "\", fallback to 640x480+nv21");
                return AML_V4L2_PIXEL_FORMAT_INVALID;
        }
    }

    private static Size parseSize(String size) {
        // input format: "<width>x<height>"
        String[] tokens = size.split("x");
        if (tokens.length != 2) {
            throw new IllegalArgumentException("Invalid size format (expected <width>x<height>): \"" + size + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid non-positive size dimension: \"" + size + "\"");
        }
        return new Size(width, height);
    }

    private static CameraAspectRatio parseCameraAspectRatio(String ar) {
        if ("sensor".equals(ar)) {
            return CameraAspectRatio.sensorAspectRatio();
        }

        String[] tokens = ar.split(":");
        if (tokens.length == 2) {
            int w = Integer.parseInt(tokens[0]);
            int h = Integer.parseInt(tokens[1]);
            return CameraAspectRatio.fromFraction(w, h);
        }

        float floatAr = Float.parseFloat(tokens[0]);
        return CameraAspectRatio.fromFloat(floatAr);
    }

    private static float parseFloat(String key, String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid float value for " + key + ": \"" + value + "\"");
        }
    }

    private static int parsePositiveIntOrDefault(String key, String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // fallback below
        }

        Ln.w("Invalid " + key + ": \"" + value + "\", fallback to " + fallback);
        return fallback;
    }

    private static NewDisplay parseNewDisplay(String newDisplay) {
        // Possible inputs:
        //  - "" (empty string)
        //  - "<width>x<height>/<dpi>"
        //  - "<width>x<height>"
        //  - "/<dpi>"
        if (newDisplay.isEmpty()) {
            return new NewDisplay();
        }

        String[] tokens = newDisplay.split("/");

        Size size;
        if (!tokens[0].isEmpty()) {
            size = parseSize(tokens[0]);
        } else {
            size = null;
        }

        int dpi;
        if (tokens.length >= 2) {
            dpi = Integer.parseInt(tokens[1]);
            if (dpi <= 0) {
                throw new IllegalArgumentException("Invalid non-positive dpi: " + tokens[1]);
            }
        } else {
            dpi = 0;
        }

        return new NewDisplay(size, dpi);
    }

    private static Pair<Orientation.Lock, Orientation> parseCaptureOrientation(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty capture orientation string");
        }

        Orientation.Lock lock;
        if (value.charAt(0) == '@') {
            // Consume '@'
            value = value.substring(1);
            if (value.isEmpty()) {
                // Only '@': lock to the initial orientation (orientation is unused)
                return Pair.create(Orientation.Lock.LockedInitial, Orientation.Orient0);
            }
            lock = Orientation.Lock.LockedValue;
        } else {
            lock = Orientation.Lock.Unlocked;
        }

        return Pair.create(lock, Orientation.getByName(value));
    }

    private static int parseDisplayImePolicy(String value) {
        switch (value) {
            case "local":
                return WindowManager.DISPLAY_IME_POLICY_LOCAL;
            case "fallback":
                return WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
            case "hide":
                return WindowManager.DISPLAY_IME_POLICY_HIDE;
            default:
                throw new IllegalArgumentException("Invalid display IME policy: " + value);
        }
    }
}
