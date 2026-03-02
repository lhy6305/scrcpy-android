package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.AssetManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Enumeration;


public class MainActivity extends Activity implements Scrcpy.ServiceCallbacks, SensorEventListener {
    private static final int REMOTE_ORIENTATION_UNKNOWN = 0;
    private static final int REMOTE_ORIENTATION_PORTRAIT = 1;
    private static final int REMOTE_ORIENTATION_LANDSCAPE = 2;
    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution";
    private static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate";
    private static int screenWidth;
    private static int screenHeight;
    private static boolean first_time = true;
    private static volatile boolean serviceBound = false;
    private static boolean nav = false;
    SensorManager sensorManager;
    private SendCommands sendCommands;
    private int videoBitrate;
    private String local_ip;
    private Context context;
    private String serverAdr = null;
    private SurfaceView surfaceView;
    private Surface surface;
    private Scrcpy scrcpy;
    private long timestamp = 0;
    private byte[] fileBase64;
    private static int remote_device_width;
    private static int remote_device_height;
    private FrameLayout videoContainer;
    private LinearLayout navButtonBar;
    private int lastRemoteOrientation = REMOTE_ORIENTATION_UNKNOWN;
    private static boolean no_control = false;
    private static boolean use_amlogic_mode = false;
    private int sessionScid = -1;
    private static final SecureRandom SCID_RANDOM = new SecureRandom();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(MainActivity.this);
            serviceBound = true;
            if (first_time) {
                scrcpy.start(surface, serverAdr, screenHeight, screenWidth, sessionScid);

                // Wait for scrcpy socket connection off the UI thread (avoid ANR).
                new Thread(() -> {
                    int count = 100;
                    while (count != 0 && serviceBound && scrcpy != null && !scrcpy.check_socket_connection()) {
                        count--;
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    final boolean connected = serviceBound && scrcpy != null && scrcpy.check_socket_connection();
                    runOnUiThread(() -> {
                        if (!connected) {
                            if (serviceBound && scrcpy != null) {
                                scrcpy.StopService();
                                try {
                                    unbindService(serviceConnection);
                                } catch (IllegalArgumentException ignore) {
                                    // ignore
                                }
                                serviceBound = false;
                            }
                            scrcpy_main();
                            Toast.makeText(MainActivity.this, R.string.error_connection_timeout, Toast.LENGTH_SHORT).show();
                            return;
                        }

                        if (!serviceBound || scrcpy == null) {
                            return;
                        }

                        int[] remRes = scrcpy.get_remote_device_resolution();
                        if (remRes[0] > 0 && remRes[1] > 0) {
                            remote_device_width = remRes[0];
                            remote_device_height = remRes[1];
                        }
                        first_time = false;
                        set_display_nd_touch();
                    });
                }, "scrcpy-conn-wait").start();
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
            }
            set_display_nd_touch();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
        }
    };

    public MainActivity() {
    }
    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (first_time) {
            scrcpy_main();
        } else {
            this.context = this;
            start_screen_copy_magic();
        }
        sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        Sensor proximity;
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);

    }


    @SuppressLint("SourceLockedOrientationActivity")
    public void scrcpy_main(){
//        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main);
        final Button startButton = findViewById(R.id.button_start);
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        setConnectingUi(false);
        AssetManager assetManager = getAssets();
        try {
            InputStream input_Stream = assetManager.open("scrcpy-server.jar");
            byte[] buffer = new byte[input_Stream.available()];
            input_Stream.read(buffer);
            fileBase64 = Base64.encode(buffer, 2);
        } catch (IOException e) {
            Log.e("Asset Manager", e.getMessage());
        }
        sendCommands = new SendCommands();

        startButton.setOnClickListener(v -> {
            local_ip = wifiIpAddress();
            getAttributes();
            if (serverAdr == null || serverAdr.isEmpty()) {
                Toast.makeText(MainActivity.this, R.string.error_server_address_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isValidIpv4(serverAdr)) {
                if (editTextServerHost != null) {
                    editTextServerHost.setError(getString(R.string.error_invalid_ip));
                }
                Toast.makeText(MainActivity.this, R.string.error_invalid_ip, Toast.LENGTH_SHORT).show();
                return;
            }

            setConnectingUi(true);
            sessionScid = generateScid();
            new Thread(() -> {
                int status = sendCommands.SendAdbCommands(context, fileBase64, serverAdr, local_ip, videoBitrate, Math.max(screenHeight, screenWidth),
                        screenWidth, screenHeight, use_amlogic_mode, sessionScid);
                runOnUiThread(() -> {
                    setConnectingUi(false);
                    if (status == 0) {
                        start_screen_copy_magic();
                    } else {
                        Toast.makeText(MainActivity.this, R.string.error_connection_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }, "scrcpy-adb-send").start();
        });
        get_saved_preferences();
    }

    private void setConnectingUi(boolean connecting) {
        Button startButton = findViewById(R.id.button_start);
        ProgressBar progressBar = findViewById(R.id.progress_connecting);
        TextView statusText = findViewById(R.id.text_status);

        if (startButton != null) {
            startButton.setEnabled(!connecting);
        }
        if (progressBar != null) {
            progressBar.setVisibility(connecting ? View.VISIBLE : View.GONE);
        }
        if (statusText != null) {
            statusText.setVisibility(connecting ? View.VISIBLE : View.GONE);
            statusText.setText(connecting ? R.string.status_connecting : "");
        }
    }


    public void get_saved_preferences(){
        this.context = this;
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        final Switch aSwitch0 = findViewById(R.id.switch0);
        final Switch aSwitch1 = findViewById(R.id.switch1);
        final Switch aSwitch2 = findViewById(R.id.switch2);
        editTextServerHost.setText(context.getSharedPreferences(PREFERENCE_KEY, 0).getString("Server Address", ""));
        aSwitch0.setChecked(context.getSharedPreferences(PREFERENCE_KEY, 0).getBoolean("No Control", false));
        aSwitch1.setChecked(context.getSharedPreferences(PREFERENCE_KEY, 0).getBoolean("Nav Switch", false));
        aSwitch2.setChecked(context.getSharedPreferences(PREFERENCE_KEY, 0).getBoolean("Amlogic Mode", false));
        setSpinner(R.array.options_resolution_values, R.id.spinner_video_resolution, PREFERENCE_SPINNER_RESOLUTION);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, PREFERENCE_SPINNER_BITRATE);
        if(aSwitch0.isChecked()){
            aSwitch1.setVisibility(View.GONE);
        }

        aSwitch0.setOnClickListener(v -> {
            if(aSwitch0.isChecked()){
                aSwitch1.setVisibility(View.GONE);
            }else{
                aSwitch1.setVisibility(View.VISIBLE);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    public void set_display_nd_touch() {
        if (!no_control) {
            surfaceView.setOnTouchListener((v, event) -> scrcpy.touchevent(event, surfaceView.getWidth(), surfaceView.getHeight()));
        }

        if (nav && !no_control) {
            final Button backButton = findViewById(R.id.back_button);
            final Button homeButton = findViewById(R.id.home_button);
            final Button appswitchButton = findViewById(R.id.appswitch_button);

            backButton.setOnClickListener(v -> scrcpy.sendKeyevent(4));

            homeButton.setOnClickListener(v -> scrcpy.sendKeyevent(3));

            appswitchButton.setOnClickListener(v -> scrcpy.sendKeyevent(187));
        }
        updateVideoViewportInsets();
        syncOrientationWithRemote();
        applySurfaceAspectRatio();
    }

    private void syncOrientationWithRemote() {
        if (remote_device_width <= 0 || remote_device_height <= 0) {
            return;
        }

        int remoteOrientation = remote_device_width >= remote_device_height ? REMOTE_ORIENTATION_LANDSCAPE : REMOTE_ORIENTATION_PORTRAIT;
        if (remoteOrientation == lastRemoteOrientation) {
            return;
        }
        lastRemoteOrientation = remoteOrientation;

        int target = remoteOrientation == REMOTE_ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (getRequestedOrientation() != target) {
            setRequestedOrientation(target);
        }
    }

    private void applySurfaceAspectRatio() {
        if (videoContainer == null || surfaceView == null) {
            return;
        }
        if (remote_device_width <= 0 || remote_device_height <= 0) {
            return;
        }

        int containerWidth = videoContainer.getWidth();
        int containerHeight = videoContainer.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            videoContainer.post(this::applySurfaceAspectRatio);
            return;
        }

        float aspect = remote_device_width / (float) remote_device_height;
        int targetWidth = containerWidth;
        int targetHeight = Math.round(targetWidth / aspect);
        if (targetHeight > containerHeight) {
            targetHeight = containerHeight;
            targetWidth = Math.round(targetHeight * aspect);
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (params == null) {
            params = new FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER);
        } else {
            params.width = targetWidth;
            params.height = targetHeight;
            params.gravity = Gravity.CENTER;
        }
        surfaceView.setLayoutParams(params);
    }

    private void updateVideoViewportInsets() {
        if (videoContainer == null) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoContainer.getLayoutParams();
        if (params == null) {
            return;
        }
        // Keep video viewport full-screen. Internal nav bar is always overlay.
        int expectedBottomMargin = 0;
        if (params.bottomMargin != expectedBottomMargin) {
            params.bottomMargin = expectedBottomMargin;
            videoContainer.setLayoutParams(params);
        }
    }

    @SuppressWarnings("deprecation")
    private void consumeRootInsets() {
        View root = findViewById(R.id.container1);
        if (root == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((v, insets) -> WindowInsets.CONSUMED);
        } else {
            root.setOnApplyWindowInsetsListener((v, insets) -> insets.consumeSystemWindowInsets());
        }
        root.requestApplyInsets();
    }

    private void setSpinner(final int textArrayOptionResId, final int textViewResId, final String preferenceId) {

        final Spinner spinner = findViewById(textViewResId);
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(this, textArrayOptionResId, android.R.layout.simple_spinner_item);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId, 0).apply();
            }
        });
        spinner.setSelection(context.getSharedPreferences(PREFERENCE_KEY, 0).getInt(preferenceId, 0));
    }

    private void getAttributes() {

        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        serverAdr = editTextServerHost != null ? editTextServerHost.getText().toString().trim() : "";
        context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString("Server Address", serverAdr).apply();
        final Spinner videoResolutionSpinner = findViewById(R.id.spinner_video_resolution);
        final Spinner videoBitrateSpinner = findViewById(R.id.spinner_video_bitrate);
        final Switch a_Switch0 = findViewById(R.id.switch0);
        no_control = a_Switch0.isChecked();
        final Switch a_Switch1 = findViewById(R.id.switch1);
        final Switch a_Switch2 = findViewById(R.id.switch2);
        nav = a_Switch1.isChecked();
        use_amlogic_mode = a_Switch2.isChecked();
        context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putBoolean("No Control", no_control).apply();
        context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putBoolean("Nav Switch", nav).apply();
        context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putBoolean("Amlogic Mode", use_amlogic_mode).apply();

        final String[] videoResolutions = getResources().getStringArray(R.array.options_resolution_values)[videoResolutionSpinner.getSelectedItemPosition()].split("x");
            screenWidth = Integer.parseInt(videoResolutions[0]);
            screenHeight = Integer.parseInt(videoResolutions[1]);
            videoBitrate = getResources().getIntArray(R.array.options_bitrate_values)[videoBitrateSpinner.getSelectedItemPosition()];
    }

    @SuppressLint("ClickableViewAccessibility")
    private void start_screen_copy_magic() {
//        Log.e("Scrcpy: ","Starting scrcpy service");
            setContentView(R.layout.surface);
            applyImmersiveMode();
            consumeRootInsets();
            surfaceView = findViewById(R.id.decoder_surface);
            videoContainer = findViewById(R.id.video_container);
            navButtonBar = findViewById(R.id.nav_button_bar);
            SurfaceHolder holder = surfaceView.getHolder();
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    surface = holder.getSurface();
                    if (serviceBound && scrcpy != null) {
                        scrcpy.setParms(surface, screenWidth, screenHeight);
                    }
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    surface = holder.getSurface();
                    if (serviceBound && scrcpy != null) {
                        scrcpy.setParms(surface, screenWidth, screenHeight);
                    }
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    surface = null;
                }
            });
            surface = holder.getSurface();
            videoContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> applySurfaceAspectRatio());
        if(nav && !no_control) {
            navButtonBar.setVisibility(LinearLayout.VISIBLE);
        }else {
            navButtonBar.setVisibility(LinearLayout.GONE);
        }
            updateVideoViewportInsets();
            start_Scrcpy_service();
    }


    protected String wifiIpAddress() {
//https://stackoverflow.com/questions/6064510/how-to-get-ip-address-of-the-device-from-code
        try {
            InetAddress ipv4 = null;
            InetAddress ipv6 = null;
            for (Enumeration<NetworkInterface> en = NetworkInterface
                    .getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface int_f = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = int_f
                        .getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (inetAddress instanceof Inet6Address) {
                        ipv6 = inetAddress;
                        continue;
                    }
                    if (inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        ipv4 = inetAddress;
                        continue;
                    }
                    return inetAddress.getHostAddress();
                }
            }
            if (ipv6 != null) {
                return ipv6.getHostAddress();
            }
            if (ipv4 != null) {
                return ipv4.getHostAddress();
            }
            return null;
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private static int generateScid() {
        return SCID_RANDOM.nextInt() & 0x7fffffff;
    }

    private static boolean isValidIpv4(String host) {
        if (host == null) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return false;
            }
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }


    private void start_Scrcpy_service() {
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        remote_device_width = width;
        remote_device_height = height;
        runOnUiThread(() -> {
            TextView connectingOverlay = findViewById(R.id.text_connecting_overlay);
            if (connectingOverlay != null) {
                connectingOverlay.setVisibility(View.GONE);
            }
            syncOrientationWithRemote();
            applyImmersiveMode();
            updateVideoViewportInsets();
            applySurfaceAspectRatio();
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersiveMode();
        updateVideoViewportInsets();
        applySurfaceAspectRatio();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (serviceBound) {
            scrcpy.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!first_time) {
            applyImmersiveMode();
            updateVideoViewportInsets();
            if (serviceBound) {
                scrcpy.resume();
                applySurfaceAspectRatio();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !first_time) {
            applyImmersiveMode();
            updateVideoViewportInsets();
            applySurfaceAspectRatio();
        }
    }

    private void applyImmersiveMode() {
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);

        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void stopScrcpyServiceIfRunning() {
        if (!serviceBound) {
            return;
        }
        if (scrcpy != null) {
            scrcpy.StopService();
        }
        try {
            unbindService(serviceConnection);
        } catch (IllegalArgumentException ignore) {
            // ignore
        }
        serviceBound = false;
    }

    @Override
    public void onBackPressed() {
        if (timestamp == 0) {
            timestamp = SystemClock.uptimeMillis();
            Toast.makeText(this, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show();
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < timestamp + 1000) {
                timestamp = 0;
                stopScrcpyServiceIfRunning();
                finish();
                return;
            }
            timestamp = 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (sensorEvent.values[0] == 0) {
                if (serviceBound) {
                    scrcpy.sendKeyevent(28);
                }
            } else {
                if (serviceBound) {
                    scrcpy.sendKeyevent(29);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

}
