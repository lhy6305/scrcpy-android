#!/usr/bin/env python3
"""
Minimal local client probe for scrcpy amlogic_v4l2 server.

Usage example (PowerShell):
  python tools/amlogic_client_probe.py --duration 90 --auto-reset-on-stall 3.0
"""

from __future__ import annotations

import argparse
import os
import random
import socket
import struct
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


PACKET_FLAG_CONFIG = 1 << 63
PACKET_FLAG_KEY_FRAME = 1 << 62
CONTROL_MSG_TYPE_RESET_VIDEO = 17
DEVICE_NAME_FIELD_LENGTH = 64


@dataclass
class PacketStats:
    packets: int = 0
    media_packets: int = 0
    config_packets: int = 0
    key_packets: int = 0
    bytes_total: int = 0
    last_packet_ts: float = 0.0
    last_media_ts: float = 0.0
    last_key_ts: float = 0.0
    resets_sent: int = 0


def run_cmd(args: list[str], check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(
        args,
        check=False,
        capture_output=capture,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and proc.returncode != 0:
        out = proc.stdout.strip()
        err = proc.stderr.strip()
        raise RuntimeError(f"Command failed ({proc.returncode}): {' '.join(args)}\nSTDOUT:\n{out}\nSTDERR:\n{err}")
    return proc


def adb_cmd(serial: Optional[str], *args: str) -> list[str]:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd.extend(args)
    return cmd


def recv_exact(sock: socket.socket, size: int, timeout_s: float) -> Optional[bytes]:
    end = time.time() + timeout_s
    chunks = []
    remaining = size
    while remaining > 0:
        now = time.time()
        if now >= end:
            return None
        sock.settimeout(max(0.01, end - now))
        try:
            data = sock.recv(remaining)
        except socket.timeout:
            continue
        if not data:
            return None
        chunks.append(data)
        remaining -= len(data)
    return b"".join(chunks)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Probe scrcpy amlogic_v4l2 stream from local host")
    parser.add_argument("--serial", default=None, help="adb device serial, optional")
    parser.add_argument("--jar", default="release-out/scrcpy-server.jar", help="local path to scrcpy-server.jar")
    parser.add_argument("--remote-jar", default="/data/local/tmp/scrcpy-server.jar", help="remote jar path on device")
    parser.add_argument("--port", type=int, default=27183, help="local tcp port for adb forward")
    parser.add_argument("--duration", type=float, default=60.0, help="probe duration in seconds")
    parser.add_argument("--startup-timeout", type=float, default=15.0, help="startup handshake timeout seconds")
    parser.add_argument("--read-timeout", type=float, default=1.0, help="socket read timeout seconds")
    parser.add_argument("--auto-reset-on-stall", type=float, default=0.0, help="send RESET_VIDEO if no media packet for N seconds (0 disables)")
    parser.add_argument("--logcat-file", default="tools/amlogic_probe_logcat.txt", help="file to dump filtered logcat")
    parser.add_argument("--simulate-switch", action="store_true", default=False, help="simulate page switching by sending keyevents over adb shell")
    parser.add_argument("--switch-interval", type=float, default=2.0, help="seconds between switch keyevents")
    parser.add_argument(
        "--switch-sequence",
        default="KEYCODE_HOME,KEYCODE_DPAD_DOWN,KEYCODE_DPAD_RIGHT,KEYCODE_DPAD_CENTER,KEYCODE_BACK",
        help="comma separated keyevents for --simulate-switch",
    )

    parser.add_argument("--scid", default=None, help="8-hex scid (without 0x), auto-random if omitted")
    parser.add_argument("--video", action="store_true", default=True, help="enable video stream")
    parser.add_argument("--audio", action="store_true", default=False, help="enable audio stream")
    parser.add_argument("--control", action="store_true", default=True, help="enable control stream")
    parser.add_argument("--max-fps", type=int, default=30)
    parser.add_argument("--video-bit-rate", type=int, default=8_000_000)
    parser.add_argument("--max-size", type=int, default=1280)
    parser.add_argument("--log-level", default="info")
    parser.add_argument("--version", default="3.3.4")
    parser.add_argument("--video-codec-options", default="repeat-previous-frame-after:long=0")

    parser.add_argument("--amlogic-v4l2", action="store_true", default=True)
    parser.add_argument("--amlogic-instance", type=int, default=1)
    parser.add_argument("--amlogic-source-type", type=int, default=1)
    parser.add_argument("--amlogic-width", type=int, default=1280)
    parser.add_argument("--amlogic-height", type=int, default=720)
    parser.add_argument("--amlogic-fps", type=int, default=30)
    parser.add_argument("--amlogic-reqbufs", type=int, default=4)
    parser.add_argument("--amlogic-format", default="nv21")

    return parser.parse_args()


def build_server_command(args: argparse.Namespace, scid_hex: str) -> str:
    opts = [
        "CLASSPATH=" + args.remote_jar,
        "app_process",
        "/",
        "com.genymobile.scrcpy.Server",
        args.version,
        f"log_level={args.log_level}",
        f"scid={scid_hex}",
        f"video={'true' if args.video else 'false'}",
        "video_source=display",
        f"max_fps={args.max_fps}",
        f"audio={'true' if args.audio else 'false'}",
        f"video_codec_options={args.video_codec_options}",
        f"video_bit_rate={args.video_bit_rate}",
        f"control={'true' if args.control else 'false'}",
        "tunnel_forward=true",
        f"max_size={args.max_size}",
        "send_device_meta=true",
        "send_codec_meta=true",
        "send_frame_meta=true",
        "send_dummy_byte=true",
    ]
    if args.amlogic_v4l2:
        opts.extend(
            [
                "amlogic_v4l2=true",
                f"amlogic_v4l2_instance={args.amlogic_instance}",
                f"amlogic_v4l2_source_type={args.amlogic_source_type}",
                f"amlogic_v4l2_width={args.amlogic_width}",
                f"amlogic_v4l2_height={args.amlogic_height}",
                f"amlogic_v4l2_fps={args.amlogic_fps}",
                f"amlogic_v4l2_reqbufs={args.amlogic_reqbufs}",
                f"amlogic_v4l2_format={args.amlogic_format}",
            ]
        )
    return " ".join(opts)


def spawn_server(serial: Optional[str], shell_cmd: str) -> subprocess.Popen:
    cmd = adb_cmd(serial, "shell", shell_cmd)
    return subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )


def start_switch_simulator(
    serial: Optional[str],
    interval_s: float,
    sequence: list[str],
    stop_event: threading.Event,
) -> threading.Thread:
    def run() -> None:
        idx = 0
        while not stop_event.is_set():
            key = sequence[idx % len(sequence)]
            idx += 1
            try:
                run_cmd(adb_cmd(serial, "shell", "input", "keyevent", key), check=False, capture=True)
                print(f"[probe] switch keyevent={key}")
            except Exception as exc:
                print(f"[probe] WARN: switch simulator failed: {exc}")
            stop_event.wait(max(0.1, interval_s))

    thread = threading.Thread(target=run, name="switch-simulator", daemon=True)
    thread.start()
    return thread


def stream_server_output(proc: subprocess.Popen, prefix: str, stop_event: threading.Event) -> None:
    if proc.stdout is None:
        return
    for line in proc.stdout:
        if stop_event.is_set():
            return
        text = line.rstrip("\r\n")
        if text:
            print(f"{prefix}{text}")


def connect_local_stream(port: int, attempts: int = 40, wait_s: float = 0.1) -> socket.socket:
    last_exc: Optional[Exception] = None
    for _ in range(attempts):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.connect(("127.0.0.1", port))
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            return s
        except OSError as exc:
            last_exc = exc
            s.close()
            time.sleep(wait_s)
    raise RuntimeError(f"Failed to connect local stream port={port}: {last_exc}")


def connect_video_with_dummy(port: int, startup_timeout_s: float) -> tuple[socket.socket, int]:
    deadline = time.time() + startup_timeout_s
    last_err = "unknown"
    while time.time() < deadline:
        try:
            s = connect_local_stream(port, attempts=1, wait_s=0.05)
        except Exception as exc:
            last_err = str(exc)
            time.sleep(0.1)
            continue

        dummy = recv_exact(s, 1, timeout_s=0.8)
        if dummy:
            return s, dummy[0]

        try:
            s.close()
        except OSError:
            pass
        last_err = "socket connected but dummy byte not available yet"
        time.sleep(0.15)

    raise RuntimeError(f"Failed to establish video handshake before timeout: {last_err}")


def pretty_codec(codec_id: int) -> str:
    raw = codec_id.to_bytes(4, byteorder="big", signed=False)
    out = []
    for b in raw:
        if 32 <= b <= 126:
            out.append(chr(b))
        else:
            out.append(".")
    return "".join(out)


def main() -> int:
    args = parse_args()
    cwd = Path(__file__).resolve().parents[1]
    jar = (cwd / args.jar).resolve() if not os.path.isabs(args.jar) else Path(args.jar)
    if not jar.exists():
        raise FileNotFoundError(f"Jar not found: {jar}")

    logcat_path = (cwd / args.logcat_file).resolve() if not os.path.isabs(args.logcat_file) else Path(args.logcat_file)
    logcat_path.parent.mkdir(parents=True, exist_ok=True)

    scid = args.scid.lower() if args.scid else f"{random.randint(1, 0x7FFFFFFF):08x}"
    if len(scid) != 8 or any(ch not in "0123456789abcdef" for ch in scid):
        raise ValueError(f"Invalid scid: {scid}, expected 8 hex chars")
    socket_name = f"scrcpy_{scid}"

    print(f"[probe] serial={args.serial or '(default)'} scid={scid} socket={socket_name} port={args.port}")
    print(f"[probe] push jar: {jar} -> {args.remote_jar}")

    run_cmd(adb_cmd(args.serial, "push", str(jar), args.remote_jar))
    run_cmd(adb_cmd(args.serial, "logcat", "-c"))
    run_cmd(adb_cmd(args.serial, "forward", f"tcp:{args.port}", f"localabstract:{socket_name}"), check=False)

    # Best-effort stop previous server instances to avoid stale process conflicts.
    run_cmd(adb_cmd(args.serial, "shell", "command -v pkill >/dev/null 2>&1 && pkill -f com.genymobile.scrcpy.Server || true"), check=False)

    server_cmd = build_server_command(args, scid)
    print("[probe] start server command:")
    print(server_cmd)

    server_proc = spawn_server(args.serial, server_cmd)
    stop_reader = threading.Event()
    reader_thread = threading.Thread(
        target=stream_server_output,
        args=(server_proc, "[server] ", stop_reader),
        daemon=True,
    )
    reader_thread.start()
    switch_stop = threading.Event()
    switch_thread: Optional[threading.Thread] = None

    video_sock = None
    audio_sock = None
    control_sock = None
    stats = PacketStats()
    reset_cooldown_until = 0.0
    exit_code = 0
    t0 = time.time()

    try:
        video_sock, dummy_byte = connect_video_with_dummy(args.port, args.startup_timeout)
        print("[probe] video stream connected")
        print(f"[probe] video dummy byte={dummy_byte}")

        if args.audio:
            audio_sock = connect_local_stream(args.port)
            print("[probe] audio stream connected")

        if args.control:
            control_sock = connect_local_stream(args.port)
            print("[probe] control stream connected")

        dev_name_raw = recv_exact(video_sock, DEVICE_NAME_FIELD_LENGTH, timeout_s=5.0)
        if not dev_name_raw:
            raise RuntimeError("Failed to read device meta")
        device_name = dev_name_raw.split(b"\x00", 1)[0].decode("utf-8", errors="replace")
        print(f"[probe] device_name={device_name!r}")

        header = recv_exact(video_sock, 12, timeout_s=5.0)
        if not header:
            raise RuntimeError("Failed to read video header")
        codec_id, width, height = struct.unpack(">III", header)
        print(f"[probe] video_header codec=0x{codec_id:08x} ({pretty_codec(codec_id)}) size={width}x{height}")

        stats.last_packet_ts = time.time()
        stats.last_media_ts = stats.last_packet_ts
        next_stat_ts = stats.last_packet_ts + 1.0

        if args.simulate_switch:
            raw_seq = [item.strip() for item in args.switch_sequence.split(",")]
            key_seq = [item for item in raw_seq if item]
            if not key_seq:
                raise ValueError("switch-sequence is empty")
            print(f"[probe] switch simulator enabled: interval={args.switch_interval}s seq={key_seq}")
            switch_thread = start_switch_simulator(args.serial, args.switch_interval, key_seq, switch_stop)

        while True:
            now = time.time()
            if now - t0 >= args.duration:
                print("[probe] duration reached")
                break

            packet_header = recv_exact(video_sock, 12, timeout_s=args.read_timeout)
            if packet_header is None:
                now = time.time()
                if args.auto_reset_on_stall > 0 and control_sock is not None:
                    idle = now - stats.last_media_ts
                    if idle >= args.auto_reset_on_stall and now >= reset_cooldown_until:
                        control_sock.sendall(bytes([CONTROL_MSG_TYPE_RESET_VIDEO]))
                        stats.resets_sent += 1
                        reset_cooldown_until = now + max(0.8, args.auto_reset_on_stall * 0.6)
                        print(f"[probe] reset_video sent (idle={idle:.2f}s, resets={stats.resets_sent})")
                if now >= next_stat_ts:
                    print(
                        "[probe] stats "
                        f"packets={stats.packets} media={stats.media_packets} cfg={stats.config_packets} key={stats.key_packets} "
                        f"bytes={stats.bytes_total} idle={now - stats.last_media_ts:.2f}s resets={stats.resets_sent}"
                    )
                    next_stat_ts = now + 1.0
                continue

            pts_flags, packet_size = struct.unpack(">QI", packet_header)
            payload = recv_exact(video_sock, packet_size, timeout_s=max(args.read_timeout, 5.0))
            if payload is None:
                raise RuntimeError("Video payload read failed")

            now = time.time()
            stats.packets += 1
            stats.bytes_total += packet_size
            stats.last_packet_ts = now

            is_config = (pts_flags & PACKET_FLAG_CONFIG) != 0
            is_key = (pts_flags & PACKET_FLAG_KEY_FRAME) != 0
            if is_config:
                stats.config_packets += 1
            else:
                stats.media_packets += 1
                stats.last_media_ts = now
                if is_key:
                    stats.key_packets += 1
                    stats.last_key_ts = now

            if now >= next_stat_ts:
                print(
                    "[probe] stats "
                    f"packets={stats.packets} media={stats.media_packets} cfg={stats.config_packets} key={stats.key_packets} "
                    f"bytes={stats.bytes_total} idle={now - stats.last_media_ts:.2f}s resets={stats.resets_sent}"
                )
                next_stat_ts = now + 1.0

    except KeyboardInterrupt:
        print("[probe] interrupted by user")
    except Exception as exc:
        exit_code = 1
        print(f"[probe] ERROR: {exc}")
    finally:
        switch_stop.set()
        if switch_thread is not None and switch_thread.is_alive():
            switch_thread.join(timeout=1.0)

        for s in (control_sock, audio_sock, video_sock):
            if s is not None:
                try:
                    s.close()
                except OSError:
                    pass

        stop_reader.set()
        run_cmd(adb_cmd(args.serial, "shell", "command -v pkill >/dev/null 2>&1 && pkill -f com.genymobile.scrcpy.Server || true"), check=False)
        run_cmd(adb_cmd(args.serial, "forward", "--remove", f"tcp:{args.port}"), check=False)

        try:
            logcat = run_cmd(
                adb_cmd(
                    args.serial,
                    "logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "-s",
                    "scrcpy:*",
                    "amlogic-v4l2-capture:*",
                    "app_process:*",
                    "appproc:*",
                    "libc:*",
                    "DEBUG:*",
                    "adbd:*",
                    "*:S",
                ),
                check=False,
                capture=True,
            )
            logcat_path.write_text(logcat.stdout or "", encoding="utf-8")
            print(f"[probe] logcat dumped -> {logcat_path}")
        except Exception as exc:
            print(f"[probe] WARN: failed to dump logcat: {exc}")

        if server_proc.poll() is None:
            try:
                server_proc.terminate()
                server_proc.wait(timeout=2.0)
            except Exception:
                try:
                    server_proc.kill()
                except Exception:
                    pass
        print(
            "[probe] final "
            f"packets={stats.packets} media={stats.media_packets} cfg={stats.config_packets} key={stats.key_packets} "
            f"bytes={stats.bytes_total} resets={stats.resets_sent}"
        )

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
