package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.StringUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.Socket;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;
    private static final int VIDEO_PORT = 7007;
    private static final int CONTROL_PORT = 7008;
    private static final int AUDIO_PORT = 7009;

    private final Socket videoSocket;
    private final Socket audioSocket;
    private final Socket controlSocket;
    private final ControlChannel controlChannel;

    private DesktopConnection(Socket videoSocket, Socket audioSocket, Socket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;
        controlChannel = controlSocket != null ? new ControlChannel(controlSocket.getInputStream(), controlSocket.getOutputStream()) : null;
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        Socket videoSocket = null;
        Socket audioSocket = null;
        Socket controlSocket = null;
        ServerSocket videoServer = null;
        ServerSocket audioServer = null;
        ServerSocket controlServer = null;
        try {
            if (video) {
                videoServer = new ServerSocket(VIDEO_PORT);
                videoSocket = videoServer.accept();
                if (sendDummyByte) {
                    // Send one byte so the client may read() to detect a connection error.
                    videoSocket.getOutputStream().write(0);
                    videoSocket.getOutputStream().flush();
                    sendDummyByte = false;
                }
            }

            if (audio) {
                audioServer = new ServerSocket(AUDIO_PORT);
                audioSocket = audioServer.accept();
                if (sendDummyByte) {
                    audioSocket.getOutputStream().write(0);
                    audioSocket.getOutputStream().flush();
                    sendDummyByte = false;
                }
            }

            if (control) {
                controlServer = new ServerSocket(CONTROL_PORT);
                controlSocket = controlServer.accept();
                if (sendDummyByte) {
                    controlSocket.getOutputStream().write(0);
                    controlSocket.getOutputStream().flush();
                }
            }
        } catch (IOException | RuntimeException e) {
            if (videoSocket != null) {
                videoSocket.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (controlSocket != null) {
                controlSocket.close();
            }
            throw e;
        } finally {
            closeQuietly(videoServer);
            closeQuietly(audioServer);
            closeQuietly(controlServer);
        }

        return new DesktopConnection(videoSocket, audioSocket, controlSocket);
    }

    private Socket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

    public void shutdown() throws IOException {
        if (videoSocket != null) {
            shutdownQuietly(videoSocket);
        }
        if (audioSocket != null) {
            shutdownQuietly(audioSocket);
        }
        if (controlSocket != null) {
            shutdownQuietly(controlSocket);
        }
    }

    public void close() throws IOException {
        if (videoSocket != null) {
            videoSocket.close();
        }
        if (audioSocket != null) {
            audioSocket.close();
        }
        if (controlSocket != null) {
            controlSocket.close();
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        OutputStream os = getFirstSocket().getOutputStream();
        os.write(buffer, 0, buffer.length);
        os.flush();
    }

    public OutputStream getVideoOutputStream() throws IOException {
        return videoSocket != null ? videoSocket.getOutputStream() : null;
    }

    public OutputStream getAudioOutputStream() throws IOException {
        return audioSocket != null ? audioSocket.getOutputStream() : null;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }

    private static void shutdownQuietly(Socket socket) {
        try {
            socket.shutdownInput();
        } catch (IOException e) {
            // ignore
        }
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            // ignore
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
