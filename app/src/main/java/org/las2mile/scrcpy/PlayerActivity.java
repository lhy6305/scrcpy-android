package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PlayerActivity extends Activity implements Scrcpy.ServiceCallbacks, SensorEventListener {
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_WIDTH = "w";
    public static final String EXTRA_HEIGHT = "h";
    public static final String EXTRA_BITRATE = "b";
    public static final String EXTRA_NO_CONTROL = "no_control";
    public static final String EXTRA_NAV = "nav";
    public static final String EXTRA_AMLOGIC_MODE = "amlogic_mode";

    private static final int REMOTE_ORIENTATION_UNKNOWN = 0;
    private static final int REMOTE_ORIENTATION_PORTRAIT = 1;
    private static final int REMOTE_ORIENTATION_LANDSCAPE = 2;

    private static final String PREF_KEY = "default";
    private static final String PREF_FLOAT_BALL_X = "float_ball_x_ratio";
    private static final String PREF_FLOAT_BALL_Y = "float_ball_y_ratio";

    private static final SecureRandom SCID_RANDOM = new SecureRandom();

    private String serverAdr;
    private int screenWidth;
    private int screenHeight;
    private int videoBitrate;
    private boolean noControl;
    private boolean nav;
    private boolean useAmlogicMode;
    private int sessionScid = -1;

    private SurfaceView surfaceView;
    private Surface surface;
    private FrameLayout videoContainer;
    private LinearLayout navButtonBar;
    private View floatingBall;

    private FrameLayout launcherOverlay;
    private EditText launcherSearch;
    private Button launcherClose;
    private ProgressBar launcherProgress;
    private GridView launcherGrid;

    private boolean inputEnabled;
    private boolean navBarVisible;
    private boolean clipboardSyncEnabled = true;

    private final ExecutorService launcherExecutor = Executors.newSingleThreadExecutor();
    private Future<?> launcherListFuture;
    private Future<?> launcherIconFuture;

    private final List<LauncherApp> launcherAllApps = new ArrayList<>();
    private final List<LauncherApp> launcherFilteredApps = new ArrayList<>();
    private final Map<String, LauncherApp> launcherAppByPackage = new HashMap<>();
    private final Set<String> launcherIconRequested = new HashSet<>();
    private LauncherAdapter launcherAdapter;
    private LruCache<String, android.graphics.Bitmap> launcherIconMemCache;

    private volatile boolean agentDeployed = false;

    private View connectionOverlay;
    private ProgressBar streamProgress;
    private TextView connectionStatus;
    private Button connectionCancel;
    private Button connectionRetry;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable streamTimeoutRunnable;

    private Scrcpy scrcpy;
    private boolean serviceBound = false;
    private volatile boolean streamingStarted = false;

    private int remoteDeviceWidth;
    private int remoteDeviceHeight;
    private int lastRemoteOrientation = REMOTE_ORIENTATION_UNKNOWN;

    private SendCommands sendCommands;
    private Thread deployThread;

    private SensorManager sensorManager;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(PlayerActivity.this);
            scrcpy.setClipboardSyncEnabled(clipboardSyncEnabled);
            serviceBound = true;
            scrcpy.start(surface, serverAdr, screenHeight, screenWidth, sessionScid);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
            scrcpy = null;
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        serverAdr = intent != null ? intent.getStringExtra(EXTRA_IP) : null;
        screenWidth = intent != null ? intent.getIntExtra(EXTRA_WIDTH, 0) : 0;
        screenHeight = intent != null ? intent.getIntExtra(EXTRA_HEIGHT, 0) : 0;
        videoBitrate = intent != null ? intent.getIntExtra(EXTRA_BITRATE, 0) : 0;
        noControl = intent != null && intent.getBooleanExtra(EXTRA_NO_CONTROL, false);
        nav = intent != null && intent.getBooleanExtra(EXTRA_NAV, false);
        useAmlogicMode = intent != null && intent.getBooleanExtra(EXTRA_AMLOGIC_MODE, false);

        inputEnabled = !noControl;
        navBarVisible = nav;

        sessionScid = generateScid();

        setContentView(R.layout.surface);
        applyImmersiveMode();
        consumeRootInsets();

        surfaceView = findViewById(R.id.decoder_surface);
        videoContainer = findViewById(R.id.video_container);
        navButtonBar = findViewById(R.id.nav_button_bar);
        floatingBall = findViewById(R.id.floating_ball);

        launcherOverlay = findViewById(R.id.launcher_overlay);
        launcherSearch = findViewById(R.id.launcher_search);
        launcherClose = findViewById(R.id.launcher_close);
        launcherProgress = findViewById(R.id.launcher_progress);
        launcherGrid = findViewById(R.id.launcher_grid);

        connectionOverlay = findViewById(R.id.connection_overlay);
        streamProgress = findViewById(R.id.progress_stream_connecting);
        connectionStatus = findViewById(R.id.text_connection_status);
        connectionCancel = findViewById(R.id.button_connection_cancel);
        connectionRetry = findViewById(R.id.button_connection_retry);

        if (connectionCancel != null) {
            connectionCancel.setOnClickListener(v -> cancelAndFinish());
        }
        if (connectionRetry != null) {
            connectionRetry.setOnClickListener(v -> startConnection());
        }

        updateNavVisibility();
        setupFloatingBall();
        setupLauncherUi();

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

        sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        Sensor proximity = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) : null;
        if (sensorManager != null && proximity != null) {
            sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
        }

        sendCommands = new SendCommands();
        startConnection();
    }

    private void startConnection() {
        stopScrcpyServiceIfRunning();
        cancelDeployThread();

        streamingStarted = false;
        remoteDeviceWidth = 0;
        remoteDeviceHeight = 0;
        lastRemoteOrientation = REMOTE_ORIENTATION_UNKNOWN;

        showConnectingUi(R.string.status_connecting_adb, false);

        deployThread = new Thread(() -> {
            byte[] fileBase64;
            try {
                fileBase64 = loadServerJarBase64();
            } catch (IOException e) {
                runOnUiThread(() -> showErrorUi(R.string.error_adb_io));
                return;
            }

            SendCommands.Result result = sendCommands.sendAdbCommands(
                    PlayerActivity.this,
                    fileBase64,
                    serverAdr,
                    videoBitrate,
                    Math.max(screenHeight, screenWidth),
                    screenWidth,
                    screenHeight,
                    useAmlogicMode,
                    sessionScid,
                    phase -> runOnUiThread(() -> showConnectingUi(getStatusTextForPhase(phase), false))
            );

            runOnUiThread(() -> {
                if (!result.success) {
                    showErrorUi(getErrorTextForSendCommands(result.error));
                    return;
                }
                showConnectingUi(R.string.status_connecting, false);
                startScrcpyServiceAndBind();
            });
        }, "scrcpy-deploy");

        deployThread.start();
    }

    private void cancelDeployThread() {
        Thread t = deployThread;
        deployThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void startScrcpyServiceAndBind() {
        scheduleStreamTimeout();
        Intent serviceIntent = new Intent(this, Scrcpy.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    private void scheduleStreamTimeout() {
        cancelStreamTimeout();
        streamTimeoutRunnable = () -> {
            if (!streamingStarted) {
                showErrorUi(R.string.error_connection_timeout);
                stopScrcpyServiceIfRunning();
            }
        };
        mainHandler.postDelayed(streamTimeoutRunnable, 10_000L);
    }

    private void cancelStreamTimeout() {
        if (streamTimeoutRunnable != null) {
            mainHandler.removeCallbacks(streamTimeoutRunnable);
            streamTimeoutRunnable = null;
        }
    }

    private void showConnectingUi(int statusResId, boolean showRetry) {
        if (connectionOverlay != null) {
            connectionOverlay.setVisibility(View.VISIBLE);
        }
        if (floatingBall != null) {
            floatingBall.setVisibility(View.GONE);
        }
        if (launcherOverlay != null) {
            launcherOverlay.setVisibility(View.GONE);
        }
        if (streamProgress != null) {
            streamProgress.setVisibility(View.VISIBLE);
        }
        if (connectionStatus != null) {
            connectionStatus.setText(statusResId);
        }
        if (connectionRetry != null) {
            connectionRetry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
        }
    }

    private void showErrorUi(int messageResId) {
        cancelStreamTimeout();
        showConnectingUi(messageResId, true);
        if (streamProgress != null) {
            streamProgress.setVisibility(View.GONE);
        }
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show();
    }

    private void hideConnectingUi() {
        cancelStreamTimeout();
        if (connectionOverlay != null) {
            connectionOverlay.setVisibility(View.GONE);
        }
        if (floatingBall != null) {
            floatingBall.setVisibility(streamingStarted ? View.VISIBLE : View.GONE);
        }
    }

    private void cancelAndFinish() {
        cancelDeployThread();
        stopScrcpyServiceIfRunning();
        finish();
    }

    private void stopScrcpyServiceIfRunning() {
        if (serviceBound) {
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
        try {
            stopService(new Intent(this, Scrcpy.class));
        } catch (Exception ignore) {
            // ignore
        }
        scrcpy = null;
    }

    private void setupFloatingBall() {
        if (floatingBall == null) {
            return;
        }

        floatingBall.post(this::restoreFloatingBallPosition);

        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        floatingBall.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            float downRawY;
            float startX;
            float startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) {
                    return false;
                }
                View parent = (View) v.getParent();
                if (parent == null) {
                    return false;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = v.getX();
                        startY = v.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging) {
                            if (Math.hypot(dx, dy) >= touchSlop) {
                                dragging = true;
                            } else {
                                return true;
                            }
                        }

                        float newX = startX + dx;
                        float newY = startY + dy;
                        float maxX = Math.max(0, parent.getWidth() - v.getWidth());
                        float maxY = Math.max(0, parent.getHeight() - v.getHeight());
                        v.setX(clampFloat(newX, 0, maxX));
                        v.setY(clampFloat(newY, 0, maxY));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) {
                            showFloatingBallMenu();
                        } else {
                            saveFloatingBallPosition();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void restoreFloatingBallPosition() {
        if (floatingBall == null) {
            return;
        }
        View parent = (View) floatingBall.getParent();
        if (parent == null) {
            return;
        }
        int parentW = parent.getWidth() - floatingBall.getWidth();
        int parentH = parent.getHeight() - floatingBall.getHeight();
        if (parentW <= 0 || parentH <= 0) {
            return;
        }

        float xRatio = getSharedPreferences(PREF_KEY, 0).getFloat(PREF_FLOAT_BALL_X, Float.NaN);
        float yRatio = getSharedPreferences(PREF_KEY, 0).getFloat(PREF_FLOAT_BALL_Y, Float.NaN);
        if (Float.isNaN(xRatio) || Float.isNaN(yRatio)) {
            return;
        }

        float x = clampFloat(xRatio, 0, 1) * parentW;
        float y = clampFloat(yRatio, 0, 1) * parentH;
        floatingBall.setX(clampFloat(x, 0, parentW));
        floatingBall.setY(clampFloat(y, 0, parentH));
    }

    private void saveFloatingBallPosition() {
        if (floatingBall == null) {
            return;
        }
        View parent = (View) floatingBall.getParent();
        if (parent == null) {
            return;
        }
        float maxX = Math.max(1, parent.getWidth() - floatingBall.getWidth());
        float maxY = Math.max(1, parent.getHeight() - floatingBall.getHeight());

        float xRatio = clampFloat(floatingBall.getX() / maxX, 0, 1);
        float yRatio = clampFloat(floatingBall.getY() / maxY, 0, 1);
        getSharedPreferences(PREF_KEY, 0).edit()
                .putFloat(PREF_FLOAT_BALL_X, xRatio)
                .putFloat(PREF_FLOAT_BALL_Y, yRatio)
                .apply();
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void showFloatingBallMenu() {
        if (floatingBall == null) {
            return;
        }
        PopupMenu menu = new PopupMenu(this, floatingBall);
        menu.inflate(R.menu.floating_ball_menu);

        boolean connected = serviceBound && scrcpy != null && scrcpy.check_socket_connection();

        menu.getMenu().findItem(R.id.menu_toggle_nav).setChecked(navBarVisible);
        menu.getMenu().findItem(R.id.menu_toggle_control).setChecked(inputEnabled);
        menu.getMenu().findItem(R.id.menu_toggle_clipboard_sync).setChecked(clipboardSyncEnabled);

        // Disable remote control actions while not connected.
        setMenuEnabled(menu, R.id.menu_key_back, connected);
        setMenuEnabled(menu, R.id.menu_key_home, connected);
        setMenuEnabled(menu, R.id.menu_key_recents, connected);
        setMenuEnabled(menu, R.id.menu_key_power, connected);
        setMenuEnabled(menu, R.id.menu_key_volume_up, connected);
        setMenuEnabled(menu, R.id.menu_key_volume_down, connected);
        setMenuEnabled(menu, R.id.menu_key_mute, connected);
        setMenuEnabled(menu, R.id.menu_clipboard_pull_remote, connected);
        setMenuEnabled(menu, R.id.menu_clipboard_paste_local, connected);
        setMenuEnabled(menu, R.id.menu_soft_keyboard, connected);

        menu.setOnMenuItemClickListener(item -> {
            if (item == null) {
                return false;
            }
            int id = item.getItemId();
            if (id == R.id.menu_reconnect_stream) {
                reconnectStreamOnly();
                return true;
            }
            if (id == R.id.menu_redeploy_reconnect) {
                startConnection();
                return true;
            }
            if (id == R.id.menu_disconnect) {
                cancelAndFinish();
                return true;
            }

            if (id == R.id.menu_open_launcher) {
                showLauncherOverlay();
                return true;
            }

            if (id == R.id.menu_toggle_nav) {
                navBarVisible = !navBarVisible;
                updateNavVisibility();
                return true;
            }

            if (id == R.id.menu_toggle_control) {
                inputEnabled = !inputEnabled;
                setupTouchAndNavControls();
                return true;
            }

            if (id == R.id.menu_toggle_clipboard_sync) {
                clipboardSyncEnabled = !clipboardSyncEnabled;
                if (serviceBound && scrcpy != null) {
                    scrcpy.setClipboardSyncEnabled(clipboardSyncEnabled);
                }
                Toast.makeText(this, clipboardSyncEnabled ? "Clipboard sync enabled" : "Clipboard sync disabled", Toast.LENGTH_SHORT).show();
                return true;
            }

            if (id == R.id.menu_key_back) {
                sendKeyIfAllowed(KeyEvent.KEYCODE_BACK);
                return true;
            }
            if (id == R.id.menu_key_home) {
                sendKeyIfAllowed(KeyEvent.KEYCODE_HOME);
                return true;
            }
            if (id == R.id.menu_key_recents) {
                sendKeyIfAllowed(KeyEvent.KEYCODE_APP_SWITCH);
                return true;
            }
            if (id == R.id.menu_key_power) {
                sendKeyIfAllowed(KeyEvent.KEYCODE_POWER);
                return true;
            }
            if (id == R.id.menu_key_volume_up) {
                sendKeyIfAllowed(KeyEvent.KEYCODE_VOLUME_UP);
                return true;
            }
            if (id == R.id.menu_key_volume_down) {
                sendKeyIfAllowed(KeyEvent.KEYCODE_VOLUME_DOWN);
                return true;
            }
            if (id == R.id.menu_key_mute) {
                sendKeyIfAllowed(KeyEvent.KEYCODE_VOLUME_MUTE);
                return true;
            }

            if (id == R.id.menu_clipboard_pull_remote) {
                pullRemoteClipboardToLocal();
                return true;
            }
            if (id == R.id.menu_clipboard_paste_local) {
                pasteLocalClipboardToRemote();
                return true;
            }
            if (id == R.id.menu_soft_keyboard) {
                showTextInputDialog();
                return true;
            }
            return false;
        });

        menu.show();
    }

    private static void setMenuEnabled(PopupMenu menu, int itemId, boolean enabled) {
        if (menu == null) {
            return;
        }
        if (menu.getMenu() == null) {
            return;
        }
        android.view.MenuItem item = menu.getMenu().findItem(itemId);
        if (item != null) {
            item.setEnabled(enabled);
        }
    }

    private void reconnectStreamOnly() {
        stopScrcpyServiceIfRunning();
        cancelDeployThread();

        streamingStarted = false;
        remoteDeviceWidth = 0;
        remoteDeviceHeight = 0;
        lastRemoteOrientation = REMOTE_ORIENTATION_UNKNOWN;
        showConnectingUi(R.string.status_connecting, false);
        startScrcpyServiceAndBind();
    }

    private void sendKeyIfAllowed(int keyCode) {
        if (!inputEnabled) {
            Toast.makeText(this, "Control disabled", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(serviceBound && scrcpy != null)) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        scrcpy.sendKeyevent(keyCode);
    }

    private void updateNavVisibility() {
        if (navButtonBar != null) {
            navButtonBar.setVisibility(navBarVisible && inputEnabled ? View.VISIBLE : View.GONE);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchAndNavControls() {
        if (surfaceView == null || scrcpy == null) {
            return;
        }

        if (inputEnabled) {
            surfaceView.setOnTouchListener((v, event) -> scrcpy.touchevent(event, surfaceView.getWidth(), surfaceView.getHeight()));
        } else {
            surfaceView.setOnTouchListener(null);
        }

        updateNavVisibility();

        if (navBarVisible && inputEnabled) {
            final Button backButton = findViewById(R.id.back_button);
            final Button homeButton = findViewById(R.id.home_button);
            final Button appswitchButton = findViewById(R.id.appswitch_button);

            if (backButton != null) {
                backButton.setOnClickListener(v -> scrcpy.sendKeyevent(4));
            }
            if (homeButton != null) {
                homeButton.setOnClickListener(v -> scrcpy.sendKeyevent(3));
            }
            if (appswitchButton != null) {
                appswitchButton.setOnClickListener(v -> scrcpy.sendKeyevent(187));
            }
        }

        updateVideoViewportInsets();
        syncOrientationWithRemote();
        applySurfaceAspectRatio();
    }

    private void syncOrientationWithRemote() {
        if (remoteDeviceWidth <= 0 || remoteDeviceHeight <= 0) {
            return;
        }

        int remoteOrientation = remoteDeviceWidth >= remoteDeviceHeight ? REMOTE_ORIENTATION_LANDSCAPE : REMOTE_ORIENTATION_PORTRAIT;
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
        if (remoteDeviceWidth <= 0 || remoteDeviceHeight <= 0) {
            return;
        }

        int containerWidth = videoContainer.getWidth();
        int containerHeight = videoContainer.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            videoContainer.post(this::applySurfaceAspectRatio);
            return;
        }

        float aspect = remoteDeviceWidth / (float) remoteDeviceHeight;
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

    private static int generateScid() {
        return SCID_RANDOM.nextInt() & 0x7fffffff;
    }

    private byte[] loadServerJarBase64() throws IOException {
        AssetManager assetManager = getAssets();
        try (InputStream input = assetManager.open("scrcpy-server.jar")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] tmp = new byte[16 * 1024];
            int n;
            while ((n = input.read(tmp)) != -1) {
                baos.write(tmp, 0, n);
            }
            byte[] raw = baos.toByteArray();
            if (raw.length == 0) {
                throw new IOException("Failed to read scrcpy-server.jar from assets");
            }
            return Base64.encode(raw, Base64.NO_WRAP);
        }
    }

    private int getStatusTextForPhase(SendCommands.Phase phase) {
        if (phase == null) {
            return R.string.status_connecting;
        }
        switch (phase) {
            case CONNECTING_ADB:
                return R.string.status_connecting_adb;
            case OPENING_SHELL:
                return R.string.status_opening_shell;
            case WAITING_SHELL:
                return R.string.status_waiting_shell;
            case PUSHING_JAR:
                return R.string.status_pushing_server;
            case STARTING_SERVER:
                return R.string.status_starting_server;
            default:
                return R.string.status_connecting;
        }
    }

    private int getErrorTextForSendCommands(SendCommands.Error error) {
        if (error == null) {
            return R.string.error_connection_failed;
        }
        switch (error) {
            case CANCELLED:
                return R.string.error_cancelled;
            case INVALID_HOST:
                return R.string.error_adb_invalid_host;
            case CONNECTION_REFUSED:
                return R.string.error_adb_connection_refused;
            case NO_ROUTE:
                return R.string.error_adb_no_route;
            case TIMEOUT:
                return R.string.error_adb_timeout;
            case IO:
                return R.string.error_adb_io;
            default:
                return R.string.error_connection_failed;
        }
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        remoteDeviceWidth = width;
        remoteDeviceHeight = height;
        streamingStarted = true;
        hideConnectingUi();
        setupTouchAndNavControls();
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        if (connected) {
            cancelStreamTimeout();
        }
    }

    @Override
    public void onConnectionError(String message) {
        if (!streamingStarted) {
            showErrorUi(R.string.error_connection_failed);
        } else if (message != null && !message.isEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
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
        if (serviceBound && scrcpy != null) {
            scrcpy.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        updateVideoViewportInsets();
        if (serviceBound && scrcpy != null) {
            scrcpy.resume();
            applySurfaceAspectRatio();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
            updateVideoViewportInsets();
            applySurfaceAspectRatio();
        }
    }

    @Override
    public void onBackPressed() {
        cancelAndFinish();
    }

    @Override
    protected void onDestroy() {
        cancelDeployThread();
        stopScrcpyServiceIfRunning();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor != null && sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (sensorEvent.values[0] == 0) {
                if (serviceBound && scrcpy != null) {
                    scrcpy.sendKeyevent(28);
                }
            } else {
                if (serviceBound && scrcpy != null) {
                    scrcpy.sendKeyevent(29);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }
}
