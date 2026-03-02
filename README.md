# scrcpy-lite (Android)

An Android client that starts `scrcpy-server` on a remote Android device over ADB TCP (`ip:5555`) and renders the video stream locally.

## Usage

1. Enable USB debugging on the remote device.
2. Enable ADB over TCP (example):

```bash
adb tcpip 5555
```

3. Make sure the remote device and this phone are on the same network.
4. Open the app, enter the remote device IPv4 address, choose resolution/bitrate, then tap **Start**.

## Notes

- The app currently expects an IPv4 address and connects to port `5555`.
- `minSdkVersion` is `21` (Android 5.0).
- Windows encoding: the project source is UTF-8. Prefer running scripts with `chcp 65001` and reading/writing files as UTF-8.
