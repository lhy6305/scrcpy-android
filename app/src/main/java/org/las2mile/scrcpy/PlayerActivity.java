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
import android.util.Log;
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

import com.tananaev.adblib.AdbConnection;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerActivity extends Activity implements Scrcpy.ServiceCallbacks, SensorEventListener {
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_WIDTH = "w";
    public static final String EXTRA_HEIGHT = "h";
    public static final String EXTRA_BITRATE = "b";
    public static final String EXTRA_NAV = "nav";
    public static final String EXTRA_AMLOGIC_MODE = "amlogic_mode";

    private static final int REMOTE_ORIENTATION_UNKNOWN = 0;
    private static final int REMOTE_ORIENTATION_PORTRAIT = 1;
    private static final int REMOTE_ORIENTATION_LANDSCAPE = 2;

    private static final String PREF_KEY = "default";
    private static final String PREF_FLOAT_BALL_X = "float_ball_x_ratio";
    private static final String PREF_FLOAT_BALL_Y = "float_ball_y_ratio";
    private static final String APKVIEWER_AGENT_VERSION = "1";
    private static final int SHARED_ADB_OPEN_RETRY_COUNT = 3;
    private static final long SHARED_ADB_OPEN_RETRY_DELAY_MS = 160L;
    private static final long LAUNCHER_LIST_CACHE_TTL_MS = 3 * 60_000L;
    private static final long LAUNCHER_LIST_CACHE_MAX_AGE_MS = 24 * 60 * 60_000L;
    private static final String LAUNCHER_LIST_CACHE_HEADER_PREFIX = "APP_CACHE_V1|";
    private static final boolean ENABLE_PROXIMITY_POWER_TOGGLE = false;

    private static final SecureRandom SCID_RANDOM = new SecureRandom();

    private String serverAdr;
    private int screenWidth;
    private int screenHeight;
    private int videoBitrate;
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
    private final AtomicLong launcherListRequestSeq = new AtomicLong(0);
    private final AtomicBoolean launcherStartInFlight = new AtomicBoolean(false);
    private final AtomicBoolean launcherPrefetchScheduled = new AtomicBoolean(false);
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

    private volatile Scrcpy scrcpy;
    private volatile boolean serviceBound = false;
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
            scrcpy.start(surface, serverAdr, screenHeight, screenWidth, videoBitrate, useAmlogicMode, sessionScid);
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
        nav = intent != null && intent.getBooleanExtra(EXTRA_NAV, false);
        useAmlogicMode = intent != null && intent.getBooleanExtra(EXTRA_AMLOGIC_MODE, false);

        // Always start with control enabled. Disabling control is a runtime toggle that only blocks input sending.
        inputEnabled = true;
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

        if (ENABLE_PROXIMITY_POWER_TOGGLE) {
            sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
            Sensor proximity = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) : null;
            if (sensorManager != null && proximity != null) {
                sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
            }
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
        launcherPrefetchScheduled.set(false);

        showConnectingUi(R.string.status_connecting_adb, false);

        deployThread = new Thread(() -> {
            byte[] fileBase64;
            try {
                fileBase64 = loadServerJarBase64();
            } catch (IOException e) {
                runOnUiThread(() -> showErrorUi(R.string.error_adb_io));
                return;
            }

            SendCommands.Result result = sendCommands.pushServerJar(
                    PlayerActivity.this,
                    fileBase64,
                    serverAdr,
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
        // Keep the floating ball always visible so users can access reconnect/disconnect even while connecting.
        if (floatingBall != null) {
            floatingBall.setVisibility(View.VISIBLE);
            clampFloatingBallToBounds();
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
            floatingBall.setVisibility(View.VISIBLE);
            clampFloatingBallToBounds();
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

        floatingBall.post(() -> {
            restoreFloatingBallPosition();
            clampFloatingBallToBounds();
        });

        View parentView = (View) floatingBall.getParent();
        if (parentView != null) {
            parentView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    clampFloatingBallToBounds());
        }

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
        int parentW = parent.getWidth();
        int parentH = parent.getHeight();
        int ballW = floatingBall.getWidth();
        int ballH = floatingBall.getHeight();
        if (parentW <= 0 || parentH <= 0 || ballW <= 0 || ballH <= 0) {
            // Layout not ready yet. Retry after the next layout pass.
            floatingBall.post(this::restoreFloatingBallPosition);
            return;
        }
        int maxX = Math.max(0, parentW - ballW);
        int maxY = Math.max(0, parentH - ballH);

        float xRatio = getSharedPreferences(PREF_KEY, 0).getFloat(PREF_FLOAT_BALL_X, Float.NaN);
        float yRatio = getSharedPreferences(PREF_KEY, 0).getFloat(PREF_FLOAT_BALL_Y, Float.NaN);
        if (Float.isNaN(xRatio) || Float.isNaN(yRatio)) {
            clampFloatingBallToBounds();
            return;
        }

        float x = clampFloat(xRatio, 0, 1) * maxX;
        float y = clampFloat(yRatio, 0, 1) * maxY;
        floatingBall.setX(clampFloat(x, 0, maxX));
        floatingBall.setY(clampFloat(y, 0, maxY));
    }

    private void clampFloatingBallToBounds() {
        if (floatingBall == null) {
            return;
        }
        View parent = (View) floatingBall.getParent();
        if (parent == null) {
            return;
        }
        int parentW = parent.getWidth();
        int parentH = parent.getHeight();
        int ballW = floatingBall.getWidth();
        int ballH = floatingBall.getHeight();
        if (parentW <= 0 || parentH <= 0 || ballW <= 0 || ballH <= 0) {
            floatingBall.post(this::clampFloatingBallToBounds);
            return;
        }
        float maxX = Math.max(0, parentW - ballW);
        float maxY = Math.max(0, parentH - ballH);
        floatingBall.setX(clampFloat(floatingBall.getX(), 0, maxX));
        floatingBall.setY(clampFloat(floatingBall.getY(), 0, maxY));
    }

    private void saveFloatingBallPosition() {
        if (floatingBall == null) {
            return;
        }
        View parent = (View) floatingBall.getParent();
        if (parent == null) {
            return;
        }
        float maxX = Math.max(0, parent.getWidth() - floatingBall.getWidth());
        float maxY = Math.max(0, parent.getHeight() - floatingBall.getHeight());

        float xRatio = maxX > 0 ? clampFloat(floatingBall.getX() / maxX, 0, 1) : 0f;
        float yRatio = maxY > 0 ? clampFloat(floatingBall.getY() / maxY, 0, 1) : 0f;
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
                Toast.makeText(this,
                        clipboardSyncEnabled ? R.string.toast_clipboard_sync_enabled : R.string.toast_clipboard_sync_disabled,
                        Toast.LENGTH_SHORT).show();
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
        launcherPrefetchScheduled.set(false);

        showConnectingUi(R.string.status_connecting, false);
        startScrcpyServiceAndBind();
    }

    private void sendKeyIfAllowed(int keyCode) {
        if (!inputEnabled) {
            Toast.makeText(this, R.string.toast_control_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(serviceBound && scrcpy != null)) {
            Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        scrcpy.sendKeyevent(keyCode);
    }

    private void pullRemoteClipboardToLocal() {
        if (TextUtils.isEmpty(serverAdr)) {
            Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        launcherExecutor.submit(() -> {
            try {
                ensureAgentDeployed();
                String out = execAgentWithFallback("clip-get");
                String text = parseClipGet(out);
                if (text == null) {
                    runOnUiThread(() -> Toast.makeText(PlayerActivity.this, R.string.toast_remote_clipboard_empty, Toast.LENGTH_SHORT).show());
                    return;
                }
                runOnUiThread(() -> {
                    setLocalClipboardText(text);
                    Toast.makeText(PlayerActivity.this, R.string.toast_clipboard_updated, Toast.LENGTH_SHORT).show();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e("scrcpy", "Failed to pull remote clipboard", e);
                runOnUiThread(() -> Toast.makeText(PlayerActivity.this, R.string.toast_clipboard_pull_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static String parseClipGet(String output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        String[] lines = output.split("\n");
        for (String rawLine : lines) {
            String line = rawLine != null ? rawLine.trim() : "";
            if (!line.startsWith("CLIP|")) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2) {
                continue;
            }
            String b64 = parts[1];
            if (b64 == null || b64.isEmpty() || "-".equals(b64)) {
                return null;
            }
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                return new String(data, StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private void pasteLocalClipboardToRemote() {
        if (!inputEnabled) {
            Toast.makeText(this, R.string.toast_control_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(serviceBound && scrcpy != null)) {
            Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        String text = readLocalClipboardText();
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, R.string.toast_local_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        scrcpy.setClipboard(text, true);
        Toast.makeText(this, R.string.toast_pasted, Toast.LENGTH_SHORT).show();
    }

    private void showTextInputDialog() {
        if (!inputEnabled) {
            Toast.makeText(this, R.string.toast_control_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(serviceBound && scrcpy != null)) {
            Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        final EditText editText = new EditText(this);
        editText.setHint(R.string.hint_input_text);
        editText.setSingleLine(false);
        editText.setMinLines(1);
        editText.setMaxLines(4);
        editText.setTextColor(0xFFFFFFFF);
        editText.setHintTextColor(0x99FFFFFF);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_soft_keyboard))
                .setView(editText)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String text = editText.getText() != null ? editText.getText().toString() : "";
                    text = text.trim();
                    if (!text.isEmpty()) {
                        scrcpy.injectText(text);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            showKeyboard(editText);
        });
        dialog.show();
    }

    private String readLocalClipboardText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            return null;
        }
        ClipData clipData = cm.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clipData.getItemAt(0).coerceToText(this);
        return text != null ? text.toString() : null;
    }

    private void setLocalClipboardText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("scrcpy-lite", text));
    }

    private void setupLauncherUi() {
        if (launcherIconMemCache == null) {
            final int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
            final int cacheKb = Math.max(1024, maxKb / 16);
            launcherIconMemCache = new LruCache<String, android.graphics.Bitmap>(cacheKb) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    return value != null ? value.getByteCount() / 1024 : 0;
                }
            };
        }

        if (launcherAdapter == null) {
            launcherAdapter = new LauncherAdapter();
        }

        if (launcherGrid != null) {
            launcherGrid.setAdapter(launcherAdapter);
            launcherGrid.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= launcherFilteredApps.size()) {
                    return;
                }
                startRemoteApp(launcherFilteredApps.get(position));
            });
            launcherGrid.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                    // no-op
                }

                @Override
                public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    requestIconsForVisibleItems();
                }
            });
        }

        if (launcherClose != null) {
            launcherClose.setOnClickListener(v -> hideLauncherOverlay());
        }

        if (launcherSearch != null) {
            launcherSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    // no-op
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    applyLauncherFilter(s != null ? s.toString() : "");
                }

                @Override
                public void afterTextChanged(Editable s) {
                    // no-op
                }
            });
        }
    }

    private void showLauncherOverlay() {
        if (launcherOverlay == null) {
            return;
        }
        launcherOverlay.setVisibility(View.VISIBLE);
        if (launcherSearch != null) {
            launcherSearch.setText("");
        }
        if (launcherProgress != null) {
            launcherProgress.setVisibility(View.VISIBLE);
        }
        loadLauncherAppList();
        if (launcherSearch != null) {
            launcherSearch.requestFocus();
            showKeyboard(launcherSearch);
        }
    }

    private void hideLauncherOverlay() {
        if (launcherOverlay != null) {
            launcherOverlay.setVisibility(View.GONE);
        }
        if (launcherSearch != null) {
            hideKeyboard(launcherSearch);
        }
    }

    private void loadLauncherAppList() {
        if (TextUtils.isEmpty(serverAdr)) {
            Toast.makeText(this, R.string.toast_server_address_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        final long requestSeq = launcherListRequestSeq.incrementAndGet();
        LauncherListCacheSnapshot cacheSnapshot = readLauncherListCache();
        final boolean hasCachedList = cacheSnapshot != null;
        if (cacheSnapshot != null) {
            applyNewAppList(cacheSnapshot.apps);
            if (launcherProgress != null) {
                launcherProgress.setVisibility(View.GONE);
            }
            requestIconsForVisibleItems();
            if (cacheSnapshot.isFresh()) {
                return;
            }
        } else if (launcherProgress != null) {
            launcherProgress.setVisibility(View.VISIBLE);
        }

        Future<?> old = launcherListFuture;
        launcherListFuture = null;
        if (old != null) {
            old.cancel(true);
        }

        launcherListFuture = launcherExecutor.submit(() -> {
            try {
                ensureAgentDeployed();
                String output = execAgentWithFallback("list");
                List<LauncherApp> apps = parseAppList(output);
                writeLauncherListCache(output);
                runOnUiThread(() -> {
                    if (isFinishing() || requestSeq != launcherListRequestSeq.get()) {
                        return;
                    }
                    applyNewAppList(apps);
                    if (launcherProgress != null) {
                        launcherProgress.setVisibility(View.GONE);
                    }
                    requestIconsForVisibleItems();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e("scrcpy", "Failed to load launcher app list", e);
                runOnUiThread(() -> {
                    if (isFinishing() || requestSeq != launcherListRequestSeq.get()) {
                        return;
                    }
                    if (launcherProgress != null) {
                        launcherProgress.setVisibility(View.GONE);
                    }
                    if (!hasCachedList) {
                        String msg = getString(R.string.toast_failed_load_apps) + ": " + briefError(e);
                        Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void prefetchLauncherListIfNeeded() {
        if (TextUtils.isEmpty(serverAdr)) {
            return;
        }
        if (!launcherPrefetchScheduled.compareAndSet(false, true)) {
            return;
        }
        launcherExecutor.submit(() -> {
            try {
                LauncherListCacheSnapshot cacheSnapshot = readLauncherListCache();
                if (cacheSnapshot != null && cacheSnapshot.isFresh()) {
                    return;
                }
                ensureAgentDeployed();
                String output = execAgentWithFallback("list");
                writeLauncherListCache(output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                launcherPrefetchScheduled.set(false);
            } catch (Exception e) {
                launcherPrefetchScheduled.set(false);
                Log.w("scrcpy", "Launcher prefetch failed: " + briefError(e));
            }
        });
    }

    private void applyNewAppList(List<LauncherApp> apps) {
        String query = launcherSearch != null && launcherSearch.getText() != null
                ? launcherSearch.getText().toString()
                : "";
        launcherAllApps.clear();
        launcherFilteredApps.clear();
        launcherAppByPackage.clear();
        launcherIconRequested.clear();

        if (apps != null) {
            launcherAllApps.addAll(apps);
            for (LauncherApp app : apps) {
                launcherAppByPackage.put(app.packageName, app);
            }
        }
        if (query.trim().isEmpty()) {
            launcherFilteredApps.addAll(launcherAllApps);
            if (launcherAdapter != null) {
                launcherAdapter.notifyDataSetChanged();
            }
            return;
        }
        applyLauncherFilter(query);
    }

    private void applyLauncherFilter(String query) {
        String q = query != null ? query.trim() : "";
        String qLower = q.toLowerCase(Locale.getDefault());

        launcherFilteredApps.clear();
        if (qLower.isEmpty()) {
            launcherFilteredApps.addAll(launcherAllApps);
        } else {
            for (LauncherApp app : launcherAllApps) {
                if (app.labelLower.contains(qLower) || app.packageNameLower.contains(qLower)) {
                    launcherFilteredApps.add(app);
                }
            }
        }
        if (launcherAdapter != null) {
            launcherAdapter.notifyDataSetChanged();
        }
        requestIconsForVisibleItems();
    }

    private void requestIconsForVisibleItems() {
        if (launcherGrid == null || launcherFilteredApps.isEmpty()) {
            return;
        }
        int first = launcherGrid.getFirstVisiblePosition();
        int last = launcherGrid.getLastVisiblePosition();
        if (first < 0 || last < 0) {
            return;
        }
        requestIconsForRange(first, last);
    }

    private void requestIconsForRange(int first, int last) {
        int start = Math.max(0, first);
        int end = Math.min(launcherFilteredApps.size() - 1, last);
        if (start > end) {
            return;
        }

        final int iconSize = 96;
        List<String> pkgs = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            LauncherApp app = launcherFilteredApps.get(i);
            if (app == null) {
                continue;
            }
            String key = app.iconCacheKey(iconSize);
            if (launcherIconMemCache != null && launcherIconMemCache.get(key) != null) {
                continue;
            }
            File file = getIconCacheFile(app, iconSize);
            if (file.exists()) {
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bmp != null && launcherIconMemCache != null) {
                    launcherIconMemCache.put(key, bmp);
                }
                continue;
            }
            if (launcherIconRequested.add(key)) {
                pkgs.add(app.packageName);
            }
        }

        if (!pkgs.isEmpty()) {
            fetchIconsAsync(pkgs, iconSize);
        }
    }

    private void fetchIconsAsync(List<String> packages, int sizePx) {
        if (TextUtils.isEmpty(serverAdr)) {
            return;
        }

        // Run sequentially (single-thread executor) and keep batches small to avoid huge shell output.
        final List<String> pkgs = packages != null ? new ArrayList<>(packages) : Collections.emptyList();
        launcherIconFuture = launcherExecutor.submit(() -> {
            try {
                ensureAgentDeployed();
                final int batchSize = 4;
                for (int i = 0; i < pkgs.size(); i += batchSize) {
                    checkCancelled();
                    int j = Math.min(pkgs.size(), i + batchSize);
                    List<String> batch = pkgs.subList(i, j);
                    StringBuilder args = new StringBuilder();
                    args.append("icons --size ").append(sizePx);
                    for (String pkg : batch) {
                        args.append(' ').append(pkg);
                    }
                    String out = execAgentWithFallback(args.toString());
                    applyIconOutput(out, sizePx);
                    runOnUiThread(() -> {
                        if (launcherAdapter != null) {
                            launcherAdapter.notifyDataSetChanged();
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignore) {
                // ignore
            }
        });
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Cancelled");
        }
    }

    private void applyIconOutput(String output, int sizePx) {
        if (output == null || output.isEmpty()) {
            return;
        }
        String[] lines = output.split("\n");
        for (String rawLine : lines) {
            String line = rawLine != null ? rawLine.trim() : "";
            if (!line.startsWith("ICON|")) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            String pkg = parts[1];
            String b64 = parts[2];
            if (pkg == null || pkg.isEmpty() || "-".equals(b64)) {
                continue;
            }

            LauncherApp app = launcherAppByPackage.get(pkg);
            if (app == null) {
                continue;
            }

            try {
                byte[] png = Base64.decode(b64, Base64.DEFAULT);
                if (png == null || png.length == 0) {
                    continue;
                }
                File file = getIconCacheFile(app, sizePx);
                writeFileAtomic(file, png);

                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp != null && launcherIconMemCache != null) {
                    launcherIconMemCache.put(app.iconCacheKey(sizePx), bmp);
                }
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private void ensureAgentDeployed() throws IOException, InterruptedException {
        if (agentDeployed) {
            return;
        }

        byte[] base64 = loadAssetBase64(ApkViewerAgentClient.ASSET_NAME);
        AdbConnection adb = getAdbForTools();
        if (adb != null) {
            try {
                ApkViewerAgentClient.deploy(adb, base64, null);
            } catch (IOException e) {
                ApkViewerAgentClient.deploy(this, serverAdr, base64, null);
            }
        } else {
            ApkViewerAgentClient.deploy(this, serverAdr, base64, null);
        }

        // Best-effort verify: some ROMs intermittently return empty shell output for tiny one-shot commands.
        // Do not fail hard here, let real list/icons calls decide.
        try {
            String out = execAgentWithFallback("--version");
            if (!isAgentVersionOk(out)) {
                Log.w("scrcpy", "apkviewer-agent version check did not match; continue with deployed agent");
            }
        } catch (IOException e) {
            Log.w("scrcpy", "apkviewer-agent version check failed; continue and retry on real request: " + e.getMessage());
        }
        agentDeployed = true;
    }

    private AdbConnection getAdbForTools() {
        Scrcpy service = scrcpy;
        if (!(serviceBound && service != null)) {
            return null;
        }
        return service.getAdbConnectionForTools();
    }

    private String execAgentWithFallback(String args) throws IOException, InterruptedException {
        AdbConnection adb = getAdbForTools();
        IOException sharedError = null;
        if (adb != null) {
            try {
                return execAgentWithSharedRetry(adb, args);
            } catch (IOException e) {
                sharedError = e;
                Log.w("scrcpy", "execAgent via shared ADB failed, fallback to new session: " + e.getMessage());
                // Fall back to a new ADB session for robustness.
            }
        }
        IOException fallbackError = null;
        try {
            return ApkViewerAgentClient.execAgent(this, serverAdr, args);
        } catch (IOException e) {
            fallbackError = e;
        }

        IOException controlError = null;
        try {
            return execAgentViaControl(args);
        } catch (IOException e) {
            controlError = e;
        }

        if (sharedError != null) {
            if (controlError != null) {
                throw new IOException(
                        "shared adb failed: " + briefError(sharedError)
                                + "; fallback adb failed: " + briefError(fallbackError)
                                + "; control failed: " + briefError(controlError),
                        controlError);
            }
            throw new IOException(
                    "shared adb failed: " + briefError(sharedError) + "; fallback adb failed: " + briefError(fallbackError),
                    fallbackError);
        }
        if (fallbackError != null) {
            if (controlError != null) {
                throw new IOException(
                        "fallback adb failed: " + briefError(fallbackError) + "; control failed: " + briefError(controlError),
                        controlError);
            }
            throw fallbackError;
        }
        if (controlError != null) {
            throw controlError;
        }
        throw new IOException("No execution path available");
    }

    private String execShellWithFallback(String command) throws IOException, InterruptedException {
        AdbConnection adb = getAdbForTools();
        IOException sharedError = null;
        if (adb != null) {
            try {
                return execShellWithSharedRetry(adb, command);
            } catch (IOException e) {
                sharedError = e;
                Log.w("scrcpy", "exec shell via shared ADB failed, fallback to new session: " + e.getMessage());
                // Fall back to a new ADB session for robustness.
            }
        }
        IOException fallbackError = null;
        try {
            return ApkViewerAgentClient.exec(this, serverAdr, command);
        } catch (IOException e) {
            fallbackError = e;
        }

        IOException controlError = null;
        try {
            return execShellViaControl(command);
        } catch (IOException e) {
            controlError = e;
        }

        if (sharedError != null) {
            if (controlError != null) {
                throw new IOException(
                        "shared adb failed: " + briefError(sharedError)
                                + "; fallback adb failed: " + briefError(fallbackError)
                                + "; control failed: " + briefError(controlError),
                        controlError);
            }
            throw new IOException(
                    "shared adb failed: " + briefError(sharedError) + "; fallback adb failed: " + briefError(fallbackError),
                    fallbackError);
        }
        if (fallbackError != null) {
            if (controlError != null) {
                throw new IOException(
                        "fallback adb failed: " + briefError(fallbackError) + "; control failed: " + briefError(controlError),
                        controlError);
            }
            throw fallbackError;
        }
        if (controlError != null) {
            throw controlError;
        }
        throw new IOException("No execution path available");
    }

    private String execAgentWithSharedRetry(AdbConnection adb, String args) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= SHARED_ADB_OPEN_RETRY_COUNT; attempt++) {
            try {
                return ApkViewerAgentClient.execAgent(adb, args);
            } catch (IOException e) {
                last = e;
                if (!isSharedOpenRetryable(e) || attempt >= SHARED_ADB_OPEN_RETRY_COUNT) {
                    throw e;
                }
                Log.w("scrcpy", "shared ADB agent open rejected, retry " + attempt + "/" + SHARED_ADB_OPEN_RETRY_COUNT);
                Thread.sleep(SHARED_ADB_OPEN_RETRY_DELAY_MS);
            }
        }
        throw last != null ? last : new IOException("shared adb agent exec failed");
    }

    private String execShellWithSharedRetry(AdbConnection adb, String command) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= SHARED_ADB_OPEN_RETRY_COUNT; attempt++) {
            try {
                return ApkViewerAgentClient.exec(adb, command);
            } catch (IOException e) {
                last = e;
                if (!isSharedOpenRetryable(e) || attempt >= SHARED_ADB_OPEN_RETRY_COUNT) {
                    throw e;
                }
                Log.w("scrcpy", "shared ADB shell open rejected, retry " + attempt + "/" + SHARED_ADB_OPEN_RETRY_COUNT);
                Thread.sleep(SHARED_ADB_OPEN_RETRY_DELAY_MS);
            }
        }
        throw last != null ? last : new IOException("shared adb shell exec failed");
    }

    private static boolean isSharedOpenRetryable(IOException e) {
        if (e == null) {
            return false;
        }
        if (hasCauseType(e, ConnectException.class)) {
            return true;
        }
        return hasAdblibIoCause(e);
    }

    private static boolean hasCauseType(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasAdblibIoCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException) {
                for (StackTraceElement frame : current.getStackTrace()) {
                    String className = frame.getClassName();
                    if (className != null && className.startsWith("com.tananaev.adblib.")) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String execAgentViaControl(String args) throws IOException, InterruptedException {
        Scrcpy service = scrcpy;
        if (!(serviceBound && service != null && service.check_socket_connection())) {
            throw new IOException("Control channel unavailable");
        }
        return service.execAgentViaControl(args);
    }

    private String execShellViaControl(String command) throws IOException, InterruptedException {
        Scrcpy service = scrcpy;
        if (!(serviceBound && service != null && service.check_socket_connection())) {
            throw new IOException("Control channel unavailable");
        }
        return service.execShellViaControl(command);
    }

    private static String briefError(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return name;
        }
        String normalized = msg.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 120) + "...";
        }
        return name + ": " + normalized;
    }

    private static boolean isAgentVersionOk(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String[] lines = output.split("\n");
        for (String rawLine : lines) {
            String line = rawLine != null ? rawLine.trim() : "";
            if (!line.startsWith("VERSION|")) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            return parts.length == 2 && APKVIEWER_AGENT_VERSION.equals(parts[1]);
        }
        return false;
    }

    private byte[] loadAssetBase64(String assetName) throws IOException {
        AssetManager assetManager = getAssets();
        try (InputStream input = assetManager.open(assetName)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] tmp = new byte[16 * 1024];
            int n;
            while ((n = input.read(tmp)) != -1) {
                baos.write(tmp, 0, n);
            }
            byte[] raw = baos.toByteArray();
            if (raw.length == 0) {
                throw new IOException("Failed to read asset: " + assetName);
            }
            return Base64.encode(raw, Base64.NO_WRAP);
        }
    }

    private File getLauncherListCacheFile() {
        File dir = new File(getCacheDir(), "apkviewer_lists");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String key = sanitizeFileName(serverAdr != null ? serverAdr : "unknown");
        return new File(dir, key + ".txt");
    }

    private LauncherListCacheSnapshot readLauncherListCache() {
        File cacheFile = getLauncherListCacheFile();
        if (!cacheFile.exists()) {
            return null;
        }
        try {
            byte[] raw = readFileFully(cacheFile);
            if (raw.length == 0) {
                return null;
            }
            String text = new String(raw, StandardCharsets.UTF_8);
            int lineBreak = text.indexOf('\n');
            if (lineBreak <= 0) {
                return null;
            }
            String header = text.substring(0, lineBreak).trim();
            if (!header.startsWith(LAUNCHER_LIST_CACHE_HEADER_PREFIX)) {
                return null;
            }
            long timestampMs;
            try {
                timestampMs = Long.parseLong(header.substring(LAUNCHER_LIST_CACHE_HEADER_PREFIX.length()));
            } catch (NumberFormatException e) {
                return null;
            }

            long now = System.currentTimeMillis();
            if (timestampMs <= 0 || now < timestampMs || now - timestampMs > LAUNCHER_LIST_CACHE_MAX_AGE_MS) {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
                return null;
            }

            String payload = text.substring(lineBreak + 1);
            return new LauncherListCacheSnapshot(timestampMs, parseAppList(payload));
        } catch (IOException e) {
            Log.w("scrcpy", "Failed to read launcher cache", e);
            return null;
        }
    }

    private void writeLauncherListCache(String listOutput) {
        if (listOutput == null) {
            return;
        }
        String header = LAUNCHER_LIST_CACHE_HEADER_PREFIX + System.currentTimeMillis();
        String payload = header + "\n" + listOutput;
        try {
            writeFileAtomic(getLauncherListCacheFile(), payload.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w("scrcpy", "Failed to write launcher cache", e);
        }
    }

    private static byte[] readFileFully(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private static List<LauncherApp> parseAppList(String output) {
        if (output == null || output.isEmpty()) {
            return Collections.emptyList();
        }
        List<LauncherApp> apps = new ArrayList<>();
        String[] lines = output.split("\n");
        for (String rawLine : lines) {
            String line = rawLine != null ? rawLine.trim() : "";
            if (!line.startsWith("APP|")) {
                continue;
            }
            String[] parts = line.split("\\|", 6);
            if (parts.length != 6) {
                continue;
            }
            String pkg = parts[1];
            String component = parts[2];
            boolean system = "1".equals(parts[3]);
            long version = 0;
            try {
                version = Long.parseLong(parts[4]);
            } catch (NumberFormatException ignore) {
                // ignore
            }
            String label = "";
            try {
                byte[] labelRaw = Base64.decode(parts[5], Base64.DEFAULT);
                label = new String(labelRaw, StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                // ignore
            }
            if (pkg == null || pkg.isEmpty() || component == null || component.isEmpty()) {
                continue;
            }
            apps.add(new LauncherApp(pkg, component, label, system, version));
        }
        return apps;
    }

    private void startRemoteApp(LauncherApp app) {
        if (app == null) {
            return;
        }
        if (!inputEnabled) {
            Toast.makeText(this, R.string.toast_control_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(serverAdr)) {
            Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!launcherStartInFlight.compareAndSet(false, true)) {
            return;
        }

        hideLauncherOverlay();

        try {
            launcherExecutor.submit(() -> {
                try {
                    // Explicit component start. This matches a launcher icon click best on TV boxes.
                    String cmd = "am start -n " + app.component;
                    String out = execShellWithFallback(cmd);
                    if (isAmStartSuccess(out)) {
                        return;
                    }

                    // Fallback: some apps might not start via explicit component (or component changed).
                    String outLeanback = execShellWithFallback(
                            "monkey -p " + app.packageName + " -c android.intent.category.LEANBACK_LAUNCHER 1");
                    if (isMonkeySuccess(outLeanback)) {
                        return;
                    }

                    String outLauncher = execShellWithFallback(
                            "monkey -p " + app.packageName + " -c android.intent.category.LAUNCHER 1");
                    if (isMonkeySuccess(outLauncher)) {
                        return;
                    }

                    runOnUiThread(() -> Toast.makeText(PlayerActivity.this, R.string.toast_failed_start_app, Toast.LENGTH_SHORT).show());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.e("scrcpy", "Failed to start remote app: " + app.packageName, e);
                    runOnUiThread(() -> Toast.makeText(PlayerActivity.this, R.string.toast_failed_start_app, Toast.LENGTH_SHORT).show());
                } finally {
                    launcherStartInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            launcherStartInFlight.set(false);
            throw e;
        }
    }

    private static boolean isAmStartSuccess(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.US);
        if (lower.contains("error") || lower.contains("exception")) {
            return false;
        }
        if (lower.contains("starting") || lower.contains("status: ok")) {
            return true;
        }
        // Examples: "Activity not started, its current task has been brought to the front".
        return lower.contains("brought to the front") || lower.contains("intent has been delivered");
    }

    private static boolean isMonkeySuccess(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.US);
        if (lower.contains("error") || lower.contains("exception") || lower.contains("aborted")) {
            return false;
        }
        return lower.contains("events injected: 1");
    }

    private File getIconCacheFile(LauncherApp app, int sizePx) {
        File dir = new File(getCacheDir(), "apkviewer_icons");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String safe = sanitizeFileName(app.packageName);
        return new File(dir, safe + "_" + app.versionCode + "_" + sizePx + ".png");
    }

    private static String sanitizeFileName(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    private static void writeFileAtomic(File file, byte[] data) throws IOException {
        if (file == null || data == null) {
            return;
        }
        File dir = file.getParentFile();
        if (dir != null) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.flush();
        }
        if (!tmp.renameTo(file)) {
            // Fallback: try to copy over.
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
                fos.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private void showKeyboard(View view) {
        if (view == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard(View view) {
        if (view == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private final class LauncherAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return launcherFilteredApps.size();
        }

        @Override
        public Object getItem(int position) {
            return position >= 0 && position < launcherFilteredApps.size() ? launcherFilteredApps.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View v = convertView;
            ViewHolder holder;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.launcher_item, parent, false);
                holder = new ViewHolder();
                holder.icon = v.findViewById(R.id.launcher_item_icon);
                holder.label = v.findViewById(R.id.launcher_item_label);
                v.setTag(holder);
            } else {
                holder = (ViewHolder) v.getTag();
            }

            LauncherApp app = launcherFilteredApps.get(position);
            if (holder.label != null) {
                holder.label.setText(app.label);
            }

            if (holder.icon != null) {
                android.graphics.Bitmap bmp = launcherIconMemCache != null ? launcherIconMemCache.get(app.iconCacheKey(96)) : null;
                if (bmp != null) {
                    holder.icon.setImageBitmap(bmp);
                } else {
                    holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
            }
            return v;
        }

        final class ViewHolder {
            ImageView icon;
            TextView label;
        }
    }

    private static final class LauncherApp {
        final String packageName;
        final String packageNameLower;
        final String component;
        final String label;
        final String labelLower;
        final boolean system;
        final long versionCode;

        LauncherApp(String packageName, String component, String label, boolean system, long versionCode) {
            this.packageName = packageName;
            this.packageNameLower = packageName != null ? packageName.toLowerCase(Locale.getDefault()) : "";
            this.component = component;
            this.label = label != null ? label : "";
            this.labelLower = this.label.toLowerCase(Locale.getDefault());
            this.system = system;
            this.versionCode = versionCode;
        }

        String iconCacheKey(int sizePx) {
            return packageName + "|" + versionCode + "|" + sizePx;
        }
    }

    private static final class LauncherListCacheSnapshot {
        final long timestampMs;
        final List<LauncherApp> apps;

        LauncherListCacheSnapshot(long timestampMs, List<LauncherApp> apps) {
            this.timestampMs = timestampMs;
            this.apps = apps != null ? apps : Collections.emptyList();
        }

        boolean isFresh() {
            return System.currentTimeMillis() - timestampMs <= LAUNCHER_LIST_CACHE_TTL_MS;
        }
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
            prefetchLauncherListIfNeeded();
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
        if (launcherOverlay != null && launcherOverlay.getVisibility() == View.VISIBLE) {
            hideLauncherOverlay();
            return;
        }
        cancelAndFinish();
    }

    @Override
    protected void onDestroy() {
        cancelDeployThread();
        launcherStartInFlight.set(false);
        Future<?> lf = launcherListFuture;
        launcherListFuture = null;
        if (lf != null) {
            lf.cancel(true);
        }
        Future<?> ifut = launcherIconFuture;
        launcherIconFuture = null;
        if (ifut != null) {
            ifut.cancel(true);
        }
        launcherExecutor.shutdownNow();
        stopScrcpyServiceIfRunning();
        if (ENABLE_PROXIMITY_POWER_TOGGLE && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (!ENABLE_PROXIMITY_POWER_TOGGLE) {
            return;
        }
        if (sensorEvent.sensor != null && sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            boolean near = sensorEvent.values != null
                    && sensorEvent.values.length > 0
                    && sensorEvent.values[0] < sensorEvent.sensor.getMaximumRange();
            if (near && serviceBound && scrcpy != null) {
                scrcpy.sendKeyevent(KeyEvent.KEYCODE_POWER);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }
}
