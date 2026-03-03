package org.las2mile.scrcpy;

import android.content.Context;
import android.util.Log;

import com.tananaev.adblib.AdbConnection;

import org.las2mile.scrcpy.utils.AdbUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

public class SendCommands {
    public enum Phase {
        CONNECTING_ADB,
        OPENING_SHELL,
        WAITING_SHELL,
        PUSHING_JAR,
        STARTING_SERVER,
    }

    public enum Error {
        NONE,
        CANCELLED,
        INVALID_HOST,
        CONNECTION_REFUSED,
        NO_ROUTE,
        TIMEOUT,
        KEY_ERROR,
        ADB_HANDSHAKE,
        SHELL_OPEN_FAILED,
        SHELL_PROMPT_TIMEOUT,
        IO,
        UNKNOWN,
    }

    public interface ProgressListener {
        void onProgress(Phase phase);
    }

    public static final class Result {
        public final boolean success;
        public final Error error;
        public final String message;

        private Result(boolean success, Error error, String message) {
            this.success = success;
            this.error = error;
            this.message = message;
        }

        public static Result ok() {
            return new Result(true, Error.NONE, null);
        }

        public static Result fail(Error error, String message) {
            return new Result(false, error, message);
        }
    }

    private static final int ADB_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 5_000;
    private static final long PROMPT_TIMEOUT_MS = 10_000L;
    private static final String REMOTE_SERVER_JAR_PATH = "/data/local/tmp/scrcpy-server.jar";

    static String buildServerCommand(int bitrate, int maxSize, int width, int height, boolean useAmlogicMode, int scid) {
        final StringBuilder command = new StringBuilder();
        command.append("CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 3.3.4");
        command.append(" log_level=info");
        command.append(String.format(Locale.US, " scid=%08x", scid & 0x7fffffff));
        command.append(" video=true");
        command.append(" video_source=display");
        command.append(" max_fps=30");
        command.append(" audio=true");
        command.append(" video_codec_options=repeat-previous-frame-after:long=0");
        command.append(" video_bit_rate=").append(bitrate);
        command.append(" control=true");
        command.append(" tunnel_forward=true");
        command.append(" max_size=").append(maxSize);
        // Keep server jar on device so stream-only reconnect can restart without re-push.
        command.append(" cleanup=false");
        if (useAmlogicMode) {
            command.append(" amlogic_v4l2=true");
            command.append(" amlogic_v4l2_instance=1");
            command.append(" amlogic_v4l2_source_type=1");
            command.append(" amlogic_v4l2_width=").append(width);
            command.append(" amlogic_v4l2_height=").append(height);
            command.append(" amlogic_v4l2_fps=30");
            command.append(" amlogic_v4l2_reqbufs=4");
            command.append(" amlogic_v4l2_format=nv21");
        }
        return command.toString();
    }

    public Result sendAdbCommands(Context context, final byte[] fileBase64, final String ip, int bitrate, int maxSize,
            int width, int height, boolean useAmlogicMode, int scid, ProgressListener listener) {
        if (Thread.currentThread().isInterrupted()) {
            return Result.fail(Error.CANCELLED, "Cancelled");
        }

        final String command = buildServerCommand(bitrate, maxSize, width, height, useAmlogicMode, scid);

        try {
            adbWriteAndStart(context, ip, fileBase64, command, listener);
            return Result.ok();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail(Error.CANCELLED, "Cancelled");
        } catch (UnknownHostException e) {
            return Result.fail(Error.INVALID_HOST, e.getMessage());
        } catch (ConnectException e) {
            return Result.fail(Error.CONNECTION_REFUSED, e.getMessage());
        } catch (NoRouteToHostException e) {
            return Result.fail(Error.NO_ROUTE, e.getMessage());
        } catch (SocketTimeoutException e) {
            return Result.fail(Error.TIMEOUT, e.getMessage());
        } catch (IOException e) {
            return Result.fail(Error.IO, e.getMessage());
        } catch (RuntimeException e) {
            return Result.fail(Error.UNKNOWN, e.getMessage());
        }
    }

    public Result pushServerJar(Context context, final byte[] fileBase64, final String ip, ProgressListener listener) {
        if (Thread.currentThread().isInterrupted()) {
            return Result.fail(Error.CANCELLED, "Cancelled");
        }

        try {
            adbWriteAndStart(context, ip, fileBase64, null, listener);
            return Result.ok();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail(Error.CANCELLED, "Cancelled");
        } catch (UnknownHostException e) {
            return Result.fail(Error.INVALID_HOST, e.getMessage());
        } catch (ConnectException e) {
            return Result.fail(Error.CONNECTION_REFUSED, e.getMessage());
        } catch (NoRouteToHostException e) {
            return Result.fail(Error.NO_ROUTE, e.getMessage());
        } catch (SocketTimeoutException e) {
            return Result.fail(Error.TIMEOUT, e.getMessage());
        } catch (IOException e) {
            return Result.fail(Error.IO, e.getMessage());
        } catch (RuntimeException e) {
            return Result.fail(Error.UNKNOWN, e.getMessage());
        }
    }

    public Result startServerOnly(Context context, final String ip, int bitrate, int maxSize,
            int width, int height, boolean useAmlogicMode, int scid, ProgressListener listener) {
        if (Thread.currentThread().isInterrupted()) {
            return Result.fail(Error.CANCELLED, "Cancelled");
        }

        final String command = buildServerCommand(bitrate, maxSize, width, height, useAmlogicMode, scid);

        final int maxAttempts = 6;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                adbStartOnly(context, ip, command, listener);
                return Result.ok();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.fail(Error.CANCELLED, "Cancelled");
            } catch (UnknownHostException e) {
                return Result.fail(Error.INVALID_HOST, e.getMessage());
            } catch (NoRouteToHostException e) {
                return Result.fail(Error.NO_ROUTE, e.getMessage());
            } catch (ConnectException e) {
                if (attempt == maxAttempts) {
                    return Result.fail(Error.CONNECTION_REFUSED, e.getMessage());
                }
                sleepBackoff(attempt);
            } catch (SocketTimeoutException e) {
                if (attempt == maxAttempts) {
                    return Result.fail(Error.TIMEOUT, e.getMessage());
                }
                sleepBackoff(attempt);
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    return Result.fail(Error.IO, e.getMessage());
                }
                sleepBackoff(attempt);
            } catch (RuntimeException e) {
                if (attempt == maxAttempts) {
                    return Result.fail(Error.UNKNOWN, e.getMessage());
                }
                sleepBackoff(attempt);
            }
        }
        return Result.fail(Error.UNKNOWN, "Unknown");
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(150L * attempt);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
    }

    private static void report(ProgressListener listener, Phase phase) {
        if (listener != null) {
            listener.onProgress(phase);
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Cancelled");
        }
    }

    private static void adbWriteAndStart(Context context, String ip, byte[] fileBase64, String command, ProgressListener listener)
            throws IOException, InterruptedException {
        checkCancelled();
        report(listener, Phase.CONNECTING_ADB);

        AdbUtils.AdbSession session = null;
        try {
            session = AdbUtils.connect(context, ip, ADB_PORT, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
            Log.i("scrcpy", "ADB socket connection successful");
            
            report(listener, Phase.OPENING_SHELL);
            // Kill existing server before pushing or starting
            AdbUtils.executeShellCommandWait(session.adb, "pkill -f com.genymobile.scrcpy.Server", PROMPT_TIMEOUT_MS);

            if (fileBase64 != null && fileBase64.length > 0) {
                report(listener, Phase.PUSHING_JAR);
                AdbUtils.pushFile(session.adb, fileBase64, REMOTE_SERVER_JAR_PATH, PROMPT_TIMEOUT_MS);
            }

            if (command != null && !command.trim().isEmpty()) {
                report(listener, Phase.STARTING_SERVER);
                AdbUtils.executeDetachedShellCommand(session.adb, command);
            }
        } finally {
            AdbUtils.closeQuietly(session);
        }
    }

    private static void adbStartOnly(Context context, String ip, String command, ProgressListener listener)
            throws IOException, InterruptedException {
        checkCancelled();
        report(listener, Phase.CONNECTING_ADB);

        AdbUtils.AdbSession session = null;
        try {
            session = AdbUtils.connect(context, ip, ADB_PORT, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
            Log.i("scrcpy", "ADB socket connection successful");

            report(listener, Phase.OPENING_SHELL);
            // Kill existing server
            AdbUtils.executeShellCommandWait(session.adb, "pkill -f com.genymobile.scrcpy.Server", PROMPT_TIMEOUT_MS);

            if (command != null && !command.trim().isEmpty()) {
                report(listener, Phase.STARTING_SERVER);
                AdbUtils.executeDetachedShellCommand(session.adb, command);
            }
        } finally {
            AdbUtils.closeQuietly(session);
        }
    }
}
