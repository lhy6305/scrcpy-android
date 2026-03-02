#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import socket
import subprocess
import threading
import time
from typing import Dict, List, Optional, Tuple

FLAG_CONFIG = 1 << 63
FLAG_KEY = 1 << 62
REMOTE_VIDEO_PORT = 7007


def log(msg: str) -> None:
    print(msg, flush=True)


def fmt_cmd(cmd: List[str]) -> str:
    return " ".join(cmd)


def run_cmd(cmd: List[str], timeout: float = 30.0, check: bool = True) -> subprocess.CompletedProcess:
    log(f"[CMD] {fmt_cmd(cmd)}")
    cp = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    out = (cp.stdout or "").strip()
    if out:
        log(out[:4000])
    if check and cp.returncode != 0:
        raise RuntimeError(f"command failed ({cp.returncode}): {fmt_cmd(cmd)}")
    return cp


def read_exact(sock: socket.socket, size: int, label: str, timeout: float) -> Optional[bytes]:
    buf = bytearray()
    deadline = time.time() + timeout
    heartbeat = 0
    while len(buf) < size and time.time() < deadline:
        try:
            chunk = sock.recv(size - len(buf))
            if not chunk:
                log(f"[SOCK] {label} EOF at {len(buf)}/{size}")
                return None
            buf.extend(chunk)
            log(f"[SOCK] {label} +{len(chunk)} => {len(buf)}/{size}")
        except socket.timeout:
            heartbeat += 1
            log(f"[SOCK] {label} waiting... ({heartbeat}s)")
    if len(buf) < size:
        log(f"[SOCK] {label} timeout at {len(buf)}/{size}")
        return None
    return bytes(buf)


def parse_resolution_list(raw: str) -> List[Tuple[int, int]]:
    result: List[Tuple[int, int]] = []
    for item in raw.split(","):
        token = item.strip().lower()
        if not token:
            continue
        if "x" not in token:
            raise ValueError(f"invalid resolution token: {item}")
        left, right = token.split("x", 1)
        width = int(left)
        height = int(right)
        if width <= 0 or height <= 0:
            raise ValueError(f"resolution must be positive: {item}")
        result.append((width, height))
    if not result:
        raise ValueError("resolution list is empty")
    return result


def build_server_cmd(
    adb: str,
    width: int,
    height: int,
    bitrate: int,
    max_fps: int,
    log_level: str,
) -> List[str]:
    return [
        adb,
        "shell",
        "CLASSPATH=/data/local/tmp/scrcpy-server.jar",
        "app_process",
        "/",
        "com.genymobile.scrcpy.Server",
        "3.3.4",
        f"log_level={log_level}",
        "video=true",
        "video_source=display",
        f"max_fps={max_fps}",
        "audio=false",
        "control=false",
        "tunnel_forward=false",
        "send_dummy_byte=false",
        "send_device_meta=false",
        "send_frame_meta=true",
        "send_codec_meta=true",
        "clipboard_autosync=false",
        "downsize_on_error=false",
        "cleanup=false",
        "video_codec=h264",
        f"video_bit_rate={bitrate}",
        f"max_size={max(width, height)}",
        "amlogic_v4l2=true",
        "amlogic_v4l2_instance=1",
        "amlogic_v4l2_source_type=1",
        f"amlogic_v4l2_width={width}",
        f"amlogic_v4l2_height={height}",
        "amlogic_v4l2_fps=30",
        "amlogic_v4l2_reqbufs=4",
        "amlogic_v4l2_format=nv21",
    ]


def spawn_server(cmd: List[str]) -> subprocess.Popen:
    log(f"[CMD] {fmt_cmd(cmd)}")
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )

    def pump() -> None:
        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.rstrip("\r\n")
            if line:
                log(f"[SRV] {line}")

    thread = threading.Thread(target=pump, daemon=True, name="server-log-pump")
    thread.start()
    return proc


def cleanup_server(adb: str, local_port: int, proc: Optional[subprocess.Popen]) -> None:
    try:
        run_cmd([adb, "shell", "pkill", "-f", "com.genymobile.scrcpy.Server"], timeout=10, check=False)
    except Exception as exc:
        log(f"[WARN] cleanup pkill failed: {exc}")
    try:
        run_cmd([adb, "forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)
    except Exception as exc:
        log(f"[WARN] cleanup forward remove failed: {exc}")
    if proc is not None:
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass


def test_resolution(
    adb: str,
    width: int,
    height: int,
    local_port: int,
    bitrate: int,
    max_fps: int,
    log_level: str,
    connect_retries: int,
    header_timeout: float,
    packet_timeout: float,
    packet_count: int,
) -> Dict[str, object]:
    log(f"\n===== TEST {width}x{height} =====")
    proc: Optional[subprocess.Popen] = None
    sock: Optional[socket.socket] = None
    result: Dict[str, object] = {
        "width": width,
        "height": height,
        "ok": False,
        "reason": "",
    }
    try:
        run_cmd([adb, "shell", "pkill", "-f", "com.genymobile.scrcpy.Server"], timeout=10, check=False)
        run_cmd([adb, "forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)
        run_cmd([adb, "forward", f"tcp:{local_port}", f"tcp:{REMOTE_VIDEO_PORT}"], timeout=10, check=True)

        proc = spawn_server(build_server_cmd(adb, width, height, bitrate, max_fps, log_level))

        header: Optional[bytes] = None
        for retry in range(connect_retries):
            try:
                if sock is not None:
                    try:
                        sock.close()
                    except Exception:
                        pass
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(1.0)
                sock.connect(("127.0.0.1", local_port))
                log(f"[SOCK] connected on retry={retry}")
                header = read_exact(sock, 12, "codec_header", timeout=header_timeout)
                if header is not None:
                    break
                log(f"[SOCK] retry={retry} got empty/timeout header, reconnecting")
            except Exception as exc:
                log(f"[SOCK] connect retry={retry} error={exc}")
            time.sleep(1.0)
        if header is None:
            result["reason"] = "header_timeout_or_eof"
            return result

        codec = int.from_bytes(header[0:4], "big", signed=False)
        stream_w = int.from_bytes(header[4:8], "big", signed=True)
        stream_h = int.from_bytes(header[8:12], "big", signed=True)
        log(f"[PARSE] codec=0x{codec:08x} stream={stream_w}x{stream_h}")
        result["codec"] = codec
        result["stream_w"] = stream_w
        result["stream_h"] = stream_h

        bytes_total = 12
        for idx in range(packet_count):
            packet_header = read_exact(sock, 12, f"packet{idx}_hdr", timeout=packet_timeout)
            if packet_header is None:
                result["reason"] = f"packet{idx}_header_fail"
                return result
            pts_flags = int.from_bytes(packet_header[0:8], "big", signed=False)
            packet_size = int.from_bytes(packet_header[8:12], "big", signed=False)
            is_config = bool(pts_flags & FLAG_CONFIG)
            is_key = bool(pts_flags & FLAG_KEY)
            log(f"[PARSE] packet{idx}: size={packet_size} config={is_config} key={is_key}")
            payload = read_exact(sock, packet_size, f"packet{idx}_payload", timeout=packet_timeout)
            if payload is None:
                result["reason"] = f"packet{idx}_payload_fail"
                return result
            bytes_total += 12 + packet_size

        result["ok"] = True
        result["reason"] = "ok"
        result["bytes_total"] = bytes_total
        return result
    finally:
        try:
            if sock is not None:
                sock.close()
        except Exception:
            pass
        cleanup_server(adb, local_port, proc)


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe Amlogic V4L2 stream across multiple resolutions.")
    parser.add_argument("--adb", default="adb", help="adb executable path")
    parser.add_argument("--jar", default="release-out/scrcpy-server.jar", help="local scrcpy-server.jar path")
    parser.add_argument("--resolutions", default="1280x720,1920x1080,1600x720,960x720", help="comma-separated WIDTHxHEIGHT list")
    parser.add_argument("--local-port", type=int, default=17007, help="local forwarded TCP port for video stream")
    parser.add_argument("--bitrate", type=int, default=6144000, help="video bitrate")
    parser.add_argument("--max-fps", type=int, default=30, help="max fps")
    parser.add_argument("--log-level", default="debug", choices=["verbose", "debug", "info", "warn", "error"], help="server log level")
    parser.add_argument("--connect-retries", type=int, default=15, help="connect retries (1s interval)")
    parser.add_argument("--header-timeout", type=float, default=12.0, help="seconds for codec header read")
    parser.add_argument("--packet-timeout", type=float, default=12.0, help="seconds for per-packet read")
    parser.add_argument("--packet-count", type=int, default=3, help="how many packets to parse after header")
    parser.add_argument("--logcat-lines", type=int, default=200, help="tail lines for final logcat dump")
    args = parser.parse_args()

    resolutions = parse_resolution_list(args.resolutions)

    run_cmd([args.adb, "push", args.jar, "/data/local/tmp/scrcpy-server.jar"], timeout=90, check=True)
    run_cmd([args.adb, "logcat", "-c"], timeout=20, check=False)

    summary: List[Dict[str, object]] = []
    for width, height in resolutions:
        try:
            res = test_resolution(
                adb=args.adb,
                width=width,
                height=height,
                local_port=args.local_port,
                bitrate=args.bitrate,
                max_fps=args.max_fps,
                log_level=args.log_level,
                connect_retries=args.connect_retries,
                header_timeout=args.header_timeout,
                packet_timeout=args.packet_timeout,
                packet_count=args.packet_count,
            )
        except Exception as exc:
            res = {"width": width, "height": height, "ok": False, "reason": f"exception: {exc}"}
        summary.append(res)

    log("\n===== SUMMARY =====")
    all_ok = True
    for item in summary:
        status = "PASS" if item.get("ok") else "FAIL"
        reason = item.get("reason", "")
        stream = ""
        if "stream_w" in item and "stream_h" in item:
            stream = f" stream={item['stream_w']}x{item['stream_h']}"
        log(f"{item['width']}x{item['height']}: {status} reason={reason}{stream}")
        if not item.get("ok"):
            all_ok = False

    run_cmd(
        [args.adb, "logcat", "-d", "-t", str(args.logcat_lines), "-s", "scrcpy:D", "AmlogicV4l2Encoder:D", "AmlogicV4L2:D", "*:S"],
        timeout=30,
        check=False,
    )

    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
