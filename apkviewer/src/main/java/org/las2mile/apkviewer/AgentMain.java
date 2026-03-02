package org.las2mile.apkviewer;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * apkviewer-agent is executed on the remote Android device via:
 * <pre>
 * CLASSPATH=/data/local/tmp/apkviewer-agent.jar app_process / org.las2mile.apkviewer.AgentMain list
 * </pre>
 *
 * Output is line-based ASCII to simplify host-side parsing over adb shell.
 */
public final class AgentMain {
    private static final String VERSION = "1";

    private static final String CMD_LIST = "list";
    private static final String CMD_ICONS = "icons";
    private static final String CMD_CLIP_GET = "clip-get";

    private static final int DEFAULT_ICON_SIZE = 96;

    private AgentMain() {
        // no instances
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            // Keep output machine-readable.
            System.out.println("ERR|EXCEPTION|" + safe(t.getClass().getSimpleName()) + "|" + safe(String.valueOf(t.getMessage())));
        }
    }

    private static void run(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            printUsage();
            return;
        }

        if ("--version".equals(args[0])) {
            System.out.println("VERSION|" + VERSION);
            return;
        }

        final Context context = createShellContext();
        final String cmd = args[0];
        if (CMD_LIST.equals(cmd)) {
            handleList(context);
            return;
        }
        if (CMD_ICONS.equals(cmd)) {
            handleIcons(context, args);
            return;
        }
        if (CMD_CLIP_GET.equals(cmd)) {
            handleClipGet(context);
            return;
        }

        printUsage();
    }

    private static void printUsage() {
        System.out.println("USAGE|apkviewer-agent");
        System.out.println("USAGE|--version");
        System.out.println("USAGE|list");
        System.out.println("USAGE|icons [--size N] <pkg>...");
        System.out.println("USAGE|clip-get");
    }

    private static void handleList(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            System.out.println("ERR|NO_PM");
            return;
        }

        List<AppRow> rows = new ArrayList<>();
        for (ApplicationInfo appInfo : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (!appInfo.enabled) {
                continue;
            }
            Intent launch = getPreferredLaunchIntent(pm, appInfo.packageName);
            if (launch == null) {
                continue;
            }
            ComponentName component = launch.getComponent();
            if (component == null) {
                continue;
            }

            String label = String.valueOf(pm.getApplicationLabel(appInfo));
            boolean system = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            long versionCode = 0;
            try {
                PackageInfo pi = pm.getPackageInfo(appInfo.packageName, 0);
                if (pi != null) {
                    if (Build.VERSION.SDK_INT >= 28) {
                        versionCode = pi.getLongVersionCode();
                    } else {
                        //noinspection deprecation
                        versionCode = pi.versionCode;
                    }
                }
            } catch (Throwable ignore) {
                // ignore
            }

            rows.add(new AppRow(appInfo.packageName, component.flattenToString(), label, system, versionCode));
        }

        sortByLabel(rows);
        for (AppRow row : rows) {
            System.out.println(row.toLine());
        }
        System.out.println("END|LIST");
    }

    private static void handleIcons(Context context, String[] args) {
        int size = DEFAULT_ICON_SIZE;
        List<String> pkgs = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--size".equals(a) && i + 1 < args.length) {
                try {
                    size = Integer.parseInt(args[++i]);
                } catch (NumberFormatException ignore) {
                    // ignore
                }
                continue;
            }
            pkgs.add(a);
        }

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            System.out.println("ERR|NO_PM");
            return;
        }

        for (String pkg : pkgs) {
            if (pkg == null || pkg.isEmpty()) {
                continue;
            }
            try {
                String b64 = loadIconBase64Png(pm, pkg, size);
                if (b64 == null) {
                    System.out.println("ICON|" + pkg + "|-");
                } else {
                    System.out.println("ICON|" + pkg + "|" + b64);
                }
            } catch (Throwable t) {
                System.out.println("ICON|" + pkg + "|-");
            }
        }
        System.out.println("END|ICONS");
    }

    private static void handleClipGet(Context context) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                System.out.println("CLIP|-");
                System.out.println("END|CLIP");
                return;
            }
            ClipData clipData = cm.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                System.out.println("CLIP|-");
                System.out.println("END|CLIP");
                return;
            }
            CharSequence text = clipData.getItemAt(0).coerceToText(context);
            if (text == null) {
                System.out.println("CLIP|-");
                System.out.println("END|CLIP");
                return;
            }
            String b64 = b64Utf8(text.toString());
            System.out.println("CLIP|" + b64);
            System.out.println("END|CLIP");
        } catch (Throwable t) {
            System.out.println("CLIP|-");
            System.out.println("END|CLIP");
        }
    }

    private static String loadIconBase64Png(PackageManager pm, String pkg, int size) throws Exception {
        Drawable drawable = pm.getApplicationIcon(pkg);
        if (drawable == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);

        ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
        boolean ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        if (!ok) {
            return null;
        }
        byte[] png = out.toByteArray();
        if (png.length == 0) {
            return null;
        }
        return Base64.encodeToString(png, Base64.NO_WRAP);
    }

    private static Intent getPreferredLaunchIntent(PackageManager pm, String pkg) {
        Intent leanback = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (leanback != null) {
            return leanback;
        }
        return pm.getLaunchIntentForPackage(pkg);
    }

    private static void sortByLabel(List<AppRow> rows) {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(rows, new Comparator<AppRow>() {
            @Override
            public int compare(AppRow a, AppRow b) {
                int c = collator.compare(a.label, b.label);
                if (c != 0) {
                    return c;
                }
                return a.packageName.compareTo(b.packageName);
            }
        });
    }

    private static String b64Utf8(String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class AppRow {
        final String packageName;
        final String component;
        final String label;
        final boolean system;
        final long versionCode;

        AppRow(String packageName, String component, String label, boolean system, long versionCode) {
            this.packageName = packageName;
            this.component = component;
            this.label = label;
            this.system = system;
            this.versionCode = versionCode;
        }

        String toLine() {
            // label is base64(utf-8) so the whole line stays ASCII.
            return "APP|" + packageName
                    + "|" + component
                    + "|" + (system ? "1" : "0")
                    + "|" + versionCode
                    + "|" + b64Utf8(label);
        }
    }

    /**
     * Minimal context bootstrap for `app_process`.
     * This is inspired by scrcpy's server-side workarounds, but intentionally minimal.
     */
    private static Context createShellContext() throws Exception {
        // ActivityThread activityThread = new ActivityThread();
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Constructor<?> atCtor = atClass.getDeclaredConstructor();
        atCtor.setAccessible(true);
        Object at = atCtor.newInstance();

        // ActivityThread.sCurrentActivityThread = activityThread;
        Field sCurrentField = atClass.getDeclaredField("sCurrentActivityThread");
        sCurrentField.setAccessible(true);
        sCurrentField.set(null, at);

        // activityThread.mSystemThread = true;
        try {
            Field systemThreadField = atClass.getDeclaredField("mSystemThread");
            systemThreadField.setAccessible(true);
            systemThreadField.setBoolean(at, true);
        } catch (Throwable ignore) {
            // ignore
        }

        Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context systemContext = (Context) getSystemContext.invoke(at);
        if (systemContext == null) {
            throw new IllegalStateException("systemContext is null");
        }

        return new ContextWrapper(systemContext) {
            @Override
            public String getPackageName() {
                return "com.android.shell";
            }

            @Override
            public String getOpPackageName() {
                return "com.android.shell";
            }
        };
    }
}
