package org.las2mile.scrcpy;

import android.content.Context;

import com.android.adblib.AdbConnection;
import com.android.adblib.AdbStream;

import org.las2mile.scrcpy.utils.AdbUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Deploys and executes apkviewer-agent on the remote device over ADB TCP.
 *
 * Agent is packaged as an APK, renamed to a ".jar" and executed via:
 * <pre>
 * CLASSPATH=/data/local/tmp/apkviewer-agent.jar app_process / org.las2mile.apkviewer.AgentMain ...
 * </pre>
 */
public final class ApkViewerAgentClient {
    public static final String ASSET_NAME = "apkviewer-agent.jar";
    public static final String REMOTE_JAR_PATH = "/data/local/tmp/apkviewer-agent.jar";

    private static final int ADB_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final long PROMPT_TIMEOUT_MS = 10_000L;

    private ApkViewerAgentClient() {
        // no instances
    }

    public interface ProgressListener {
        void onMessage(String message);
    }

    public static void deploy(Context context, String ip, byte[] agentJarBytes, ProgressListener listener)
            throws IOException, InterruptedException {
        if (agentJarBytes == null || agentJarBytes.length == 0) {
            throw new IOException("agentJarBytes is empty");
        }

        AdbUtils.AdbSession session = null;
        try {
            report(listener, "Connecting adb...");
            session = AdbUtils.connect(context, ip, ADB_PORT, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
            
            report(listener, "Pushing agent...");
            AdbUtils.pushFile(session.adb, agentJarBytes, REMOTE_JAR_PATH, PROMPT_TIMEOUT_MS);
            
            report(listener, "Agent deployed");
        } finally {
            AdbUtils.closeQuietly(session);
        }
    }

    public static void deploy(AdbConnection adb, byte[] agentJarBytes, ProgressListener listener)
            throws IOException, InterruptedException {
        if (adb == null) {
            throw new IOException("adb is null");
        }
        if (agentJarBytes == null || agentJarBytes.length == 0) {
            throw new IOException("agentJarBytes is empty");
        }

        report(listener, "Pushing agent...");
        AdbUtils.pushFile(adb, agentJarBytes, REMOTE_JAR_PATH, PROMPT_TIMEOUT_MS);
        report(listener, "Agent deployed");
    }

    public static String exec(Context context, String ip, String command) throws IOException, InterruptedException {
        if (command == null) {
            throw new IOException("command is null");
        }
        AdbUtils.AdbSession session = null;
        try {
            session = AdbUtils.connect(context, ip, ADB_PORT, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
            return exec(session.adb, command);
        } finally {
            AdbUtils.closeQuietly(session);
        }
    }

    public static String exec(AdbConnection adb, String command) throws IOException, InterruptedException {
        if (adb == null) {
            throw new IOException("adb is null");
        }
        if (command == null) {
            throw new IOException("command is null");
        }

        AdbStream stream = null;
        try {
            synchronized (adb) {
                stream = adb.open("shell:" + command);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (!Thread.currentThread().isInterrupted()) {
                byte[] chunk = stream.read();
                if (chunk == null) {
                    break;
                }
                if (chunk.length == 0) {
                    continue;
                }
                out.write(chunk, 0, chunk.length);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Cancelled");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            AdbUtils.closeQuietly(stream);
        }
    }

    public static String execAgent(Context context, String ip, String args) throws IOException, InterruptedException {
        String cmd = "CLASSPATH=" + REMOTE_JAR_PATH + " app_process / org.las2mile.apkviewer.AgentMain " + args;
        return exec(context, ip, cmd);
    }

    public static String execAgent(AdbConnection adb, String args) throws IOException, InterruptedException {
        String cmd = "CLASSPATH=" + REMOTE_JAR_PATH + " app_process / org.las2mile.apkviewer.AgentMain " + args;
        return exec(adb, cmd);
    }

    private static void report(ProgressListener listener, String message) {
        if (listener != null) {
            listener.onMessage(message);
        }
    }
}

