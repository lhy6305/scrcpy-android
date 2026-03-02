package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.lang.reflect.Method;

@SuppressLint("PrivateApi")
public final class SurfaceControl {

    private static final Class<?> CLASS;

    // see <https://android.googlesource.com/platform/frameworks/base.git/+/pie-release-2/core/java/android/view/SurfaceControl.java#305>
    public static final int POWER_MODE_OFF = 0;
    public static final int POWER_MODE_NORMAL = 2;

    static {
        try {
            CLASS = Class.forName("android.view.SurfaceControl");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Method getBuiltInDisplayMethod;
    private static Method setDisplayPowerModeMethod;
    private static Method getPhysicalDisplayTokenMethod;
    private static Method getPhysicalDisplayIdsMethod;
    private static Method screenshotMethod;
    private static Method screenshotWithDisplayTokenMethod;
    private static boolean screenshotWithDisplayTokenUnavailable;
    private static boolean screenshotWithDisplayTokenUnavailableLogged;

    private SurfaceControl() {
        // only static methods
    }

    public static void openTransaction() {
        try {
            CLASS.getMethod("openTransaction").invoke(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void closeTransaction() {
        try {
            CLASS.getMethod("closeTransaction").invoke(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplayProjection(IBinder displayToken, int orientation, Rect layerStackRect, Rect displayRect) {
        try {
            CLASS.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(null, displayToken, orientation, layerStackRect, displayRect);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) {
        try {
            CLASS.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, displayToken, layerStack);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplaySurface(IBinder displayToken, Surface surface) {
        try {
            CLASS.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, displayToken, surface);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static IBinder createDisplay(String name, boolean secure) throws Exception {
        return (IBinder) CLASS.getMethod("createDisplay", String.class, boolean.class).invoke(null, name, secure);
    }

    private static Method getGetBuiltInDisplayMethod() throws NoSuchMethodException {
        if (getBuiltInDisplayMethod == null) {
            // the method signature has changed in Android 10
            // <https://github.com/Genymobile/scrcpy/issues/586>
            if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
                getBuiltInDisplayMethod = CLASS.getMethod("getBuiltInDisplay", int.class);
            } else {
                getBuiltInDisplayMethod = CLASS.getMethod("getInternalDisplayToken");
            }
        }
        return getBuiltInDisplayMethod;
    }

    public static boolean hasGetBuildInDisplayMethod() {
        try {
            getGetBuiltInDisplayMethod();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static IBinder getBuiltInDisplay() {
        try {
            Method method = getGetBuiltInDisplayMethod();
            if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
                // call getBuiltInDisplay(0)
                return (IBinder) method.invoke(null, 0);
            }

            // call getInternalDisplayToken()
            return (IBinder) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return null;
        }
    }

    private static Method getGetPhysicalDisplayTokenMethod() throws NoSuchMethodException {
        if (getPhysicalDisplayTokenMethod == null) {
            getPhysicalDisplayTokenMethod = CLASS.getMethod("getPhysicalDisplayToken", long.class);
        }
        return getPhysicalDisplayTokenMethod;
    }

    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        try {
            Method method = getGetPhysicalDisplayTokenMethod();
            return (IBinder) method.invoke(null, physicalDisplayId);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return null;
        }
    }

    private static Method getGetPhysicalDisplayIdsMethod() throws NoSuchMethodException {
        if (getPhysicalDisplayIdsMethod == null) {
            getPhysicalDisplayIdsMethod = CLASS.getMethod("getPhysicalDisplayIds");
        }
        return getPhysicalDisplayIdsMethod;
    }

    public static boolean hasGetPhysicalDisplayIdsMethod() {
        try {
            getGetPhysicalDisplayIdsMethod();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static long[] getPhysicalDisplayIds() {
        try {
            Method method = getGetPhysicalDisplayIdsMethod();
            return (long[]) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return null;
        }
    }

    private static Method getSetDisplayPowerModeMethod() throws NoSuchMethodException {
        if (setDisplayPowerModeMethod == null) {
            setDisplayPowerModeMethod = CLASS.getMethod("setDisplayPowerMode", IBinder.class, int.class);
        }
        return setDisplayPowerModeMethod;
    }

    public static boolean setDisplayPowerMode(IBinder displayToken, int mode) {
        try {
            Method method = getSetDisplayPowerModeMethod();
            method.invoke(null, displayToken, mode);
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    public static void destroyDisplay(IBinder displayToken) {
        try {
            CLASS.getMethod("destroyDisplay", IBinder.class).invoke(null, displayToken);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Method getScreenshotMethod() throws NoSuchMethodException {
        if (screenshotMethod == null) {
            NoSuchMethodException last = null;
            try {
                // Prefer the 7-arg signature on Android 9: it matches screencap's native parameters.
                screenshotMethod = CLASS.getMethod("screenshot", Rect.class, int.class, int.class, int.class, int.class, boolean.class,
                        int.class);
            } catch (NoSuchMethodException e) {
                last = e;
            }
            if (screenshotMethod == null) {
                try {
                    screenshotMethod = CLASS.getMethod("screenshot", Rect.class, int.class, int.class, int.class);
                } catch (NoSuchMethodException e) {
                    last = e;
                }
            }
            if (screenshotMethod == null) {
                try {
                    // Legacy fallback.
                    screenshotMethod = CLASS.getMethod("screenshot", int.class, int.class);
                } catch (NoSuchMethodException e) {
                    last = e;
                }
            }
            if (screenshotMethod == null) {
                throw last != null ? last : new NoSuchMethodException("No compatible screenshot() method found");
            }
        }
        return screenshotMethod;
    }

    private static Method getScreenshotWithDisplayTokenMethod() throws NoSuchMethodException {
        if (screenshotWithDisplayTokenUnavailable) {
            throw new NoSuchMethodException("screenshot(displayToken,...) unavailable");
        }
        if (screenshotWithDisplayTokenMethod == null) {
            NoSuchMethodException last = null;
            try {
                // Preferred hidden API on Android 9.
                screenshotWithDisplayTokenMethod = CLASS.getMethod("screenshot", IBinder.class, Rect.class, int.class, int.class, int.class,
                        int.class, boolean.class, int.class);
            } catch (NoSuchMethodException e) {
                last = e;
            }
            if (screenshotWithDisplayTokenMethod == null) {
                try {
                    screenshotWithDisplayTokenMethod = CLASS.getMethod("screenshot", IBinder.class, Rect.class, int.class, int.class, int.class);
                } catch (NoSuchMethodException e) {
                    last = e;
                }
            }
            if (screenshotWithDisplayTokenMethod == null) {
                screenshotWithDisplayTokenUnavailable = true;
                throw last != null ? last : new NoSuchMethodException("No compatible screenshot(displayToken,...) method found");
            }
        }
        return screenshotWithDisplayTokenMethod;
    }

    public static Bitmap screenshot(Rect sourceCrop, int width, int height, int rotation) {
        try {
            Method method = getScreenshotMethod();
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 4) {
                return (Bitmap) method.invoke(null, sourceCrop, width, height, rotation);
            }
            if (params.length == 7) {
                // Match screencap defaults: capture all layers.
                return (Bitmap) method.invoke(null, sourceCrop, width, height, Integer.MIN_VALUE, Integer.MAX_VALUE, false, rotation);
            }
            if (params.length == 2) {
                return (Bitmap) method.invoke(null, width, height);
            }
            Ln.e("Unsupported screenshot() signature with " + params.length + " parameters");
            return null;
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke screenshot()", e);
            return null;
        }
    }

    public static Bitmap screenshot(IBinder displayToken, Rect sourceCrop, int width, int height, int rotation) {
        try {
            Method method = getScreenshotWithDisplayTokenMethod();
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 8) {
                // Match screencap defaults: all layers, do not use identity transform.
                return (Bitmap) method.invoke(null, displayToken, sourceCrop, width, height, Integer.MIN_VALUE, Integer.MAX_VALUE, false,
                        rotation);
            }
            if (params.length == 5) {
                return (Bitmap) method.invoke(null, displayToken, sourceCrop, width, height, rotation);
            }
            Ln.e("Unsupported screenshot(displayToken,...) signature with " + params.length + " parameters");
            return null;
        } catch (NoSuchMethodException e) {
            if (!screenshotWithDisplayTokenUnavailableLogged) {
                screenshotWithDisplayTokenUnavailableLogged = true;
                Ln.w("screenshot(displayToken,...) not available on this device, fallback will be used");
            }
            return null;
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke screenshot(displayToken,...)", e);
            return null;
        }
    }
}
