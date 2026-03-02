#!/usr/bin/env python3
"""
Latency drift probe for scrcpy-server video stream.

It starts scrcpy-server over adb, connects as a minimal video client, optionally slows packet
consumption, and reports relative latency drift based on frame PTS.
"""

from __future__ import annotations

import argparse
import random
import socket
import struct
import subprocess
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


PACKET_FLAG_CONFIG = 1 << 63
PACKET_FLAG_KEY_FRAME = 1 << 62
DEVICE_NAME_FIELD_LENGTH = 64


@dataclass
class Stats:
    packets: int = 0
    media_packets: int = 0
    config_packets: int = 0
    key_packets: int = 0
    bytes_total: int = 0
    lag_samples: int = 0
    lag_sum_ms: float = 0.0
    lag_min_ms: float = 0.0
    lag_max_ms: float = 0.0


def ensure_utf8_stdio() -> None:
    # Windows defaults to GBK in many shells; force UTF-8 to keep logs deterministic.
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except ValueError:
                pass


def run_cmd(args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        args,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and proc.returncode != 0:
        raise RuntimeError(
            f"Command failed ({proc.returncode}): {' '.join(args)}\n"
            f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )
    return proc


def adb_cmd(serial: Optional[str], *args: str) -> list[str]:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd.extend(args)
    return cmd


def recv_exact(sock: socket.socket, size: int, timeout_s: float) -> Optional[bytes]:
    end = time.time() + timeout_s
    buf = bytearray()
    while len(buf) < size:
        now = time.time()
        if now >= end:
            return None
        sock.settimeout(max(0.01, end - now))
        try:
            chunk = sock.recv(size - len(buf))
        except socket.timeout:
            continue
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Probe scrcpy server latency drift under slow client consumption")
    parser.add_argument("--serial", default=None, help="adb serial/device")
    parser.add_argument("--jar", default="app/src/main/assets/scrcpy-server.jar", help="local server jar path")
    parser.add_argument("--remote-jar", default="/data/local/tmp/scrcpy-server.jar", help="remote jar path")
    parser.add_argument("--port", type=int, default=27183, help="local forwarded tcp port")
    parser.add_argument("--duration", type=float, default=45.0, help="probe duration seconds")
    parser.add_argument("--read-timeout", type=float, default=1.0, help="socket read timeout seconds")
    parser.add_argument("--slow-read-ms", type=float, default=0.0, help="sleep after each media packet")
    parser.add_argument("--startup-timeout", type=float, default=15.0, help="startup timeout seconds")

    parser.add_argument("--version", default="3.3.4")
    parser.add_argument("--log-level", default="info")
    parser.add_argument("--max-fps", type=int, default=30)
    parser.add_argument("--max-size", type=int, default=1280)
    parser.add_argument("--video-bit-rate", type=int, default=8000000)
    parser.add_argument("--video-latency-drop", choices=["true", "false"], default="true")
    parser.add_argument("--video-latency-drop-threshold-ms", type=int, default=250)
    parser.add_argument("--video-latency-recover-threshold-ms", type=int, default=120)
    parser.add_argument("--video-sync-request-interval-ms", type=int, default=500)
    parser.add_argument("--video-force-recover-drop-count", type=int, default=120)
    parser.add_argument("--amlogic-v4l2-queue-drain-max", type=int, default=8)

    parser.add_argument("--amlogic", action="store_true", help="enable amlogic_v4l2 mode")
    parser.add_argument("--amlogic-instance", type=int, default=1)
    parser.add_argument("--amlogic-source-type", type=int, default=1)
    parser.add_argument("--amlogic-width", type=int, default=1280)
    parser.add_argument("--amlogic-height", type=int, default=720)
    parser.add_argument("--amlogic-fps", type=int, default=30)
    parser.add_argument("--amlogic-reqbufs", type=int, default=4)
    parser.add_argument("--amlogic-format", default="nv21")
    return parser.parse_args()


def build_server_command(args: argparse.Namespace, scid: str) -> str:
    opts = [
        "CLASSPATH=" + args.remote_jar,
        "app_process",
        "/",
        "com.genymobile.scrcpy.Server",
        args.version,
        f"log_level={args.log_level}",
        f"scid={scid}",
        "video=true",
        "audio=false",
        "control=false",
        "video_source=display",
        f"video_bit_rate={args.video_bit_rate}",
        f"video_latency_drop={args.video_latency_drop}",
        f"video_latency_drop_threshold_ms={args.video_latency_drop_threshold_ms}",
        f"video_latency_recover_threshold_ms={args.video_latency_recover_threshold_ms}",
        f"video_sync_request_interval_ms={args.video_sync_request_interval_ms}",
        f"video_force_recover_drop_count={args.video_force_recover_drop_count}",
        f"max_fps={args.max_fps}",
        f"max_size={args.max_size}",
        "tunnel_forward=true",
        "send_device_meta=true",
        "send_codec_meta=true",
        "send_frame_meta=true",
        "send_dummy_byte=true",
        f"amlogic_v4l2={'true' if args.amlogic else 'false'}",
    ]
    if args.amlogic:
        opts.extend(
            [
                f"amlogic_v4l2_instance={args.amlogic_instance}",
                f"amlogic_v4l2_source_type={args.amlogic_source_type}",
                f"amlogic_v4l2_width={args.amlogic_width}",
                f"amlogic_v4l2_height={args.amlogic_height}",
                f"amlogic_v4l2_fps={args.amlogic_fps}",
                f"amlogic_v4l2_reqbufs={args.amlogic_reqbufs}",
                f"amlogic_v4l2_format={args.amlogic_format}",
                f"amlogic_v4l2_queue_drain_max={args.amlogic_v4l2_queue_drain_max}",
            ]
        )
    return " ".join(opts)


def connect_with_retry(port: int, timeout_s: float) -> socket.socket:
    deadline = time.time() + timeout_s
    last_exc: Optional[Exception] = None
    while time.time() < deadline:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            sock.connect(("127.0.0.1", port))
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            return sock
        except OSError as exc:
            last_exc = exc
            sock.close()
            time.sleep(0.1)
    raise RuntimeError(f"Failed to connect tcp:{port}: {last_exc}")


def connect_video_with_dummy(port: int, startup_timeout_s: float) -> tuple[socket.socket, int]:
    deadline = time.time() + startup_timeout_s
    last_err = "unknown"
    while time.time() < deadline:
        try:
            sock = connect_with_retry(port, 1.0)
        except Exception as exc:
            last_err = str(exc)
            time.sleep(0.1)
            continue

        dummy = recv_exact(sock, 1, timeout_s=1.0)
        if dummy:
            return sock, dummy[0]

        try:
            sock.close()
        except OSError:
            pass
        last_err = "connected but dummy not received yet"
        time.sleep(0.15)

    raise RuntimeError(f"Failed to receive dummy byte before timeout: {last_err}")


def stream_server_output(proc: subprocess.Popen[str], stop_event: threading.Event) -> None:
    if proc.stdout is None:
        return
    for line in proc.stdout:
        if stop_event.is_set():
            return
        text = line.rstrip("\r\n")
        if text:
            print(f"[server] {text}")


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    sorted_vals = sorted(values)
    idx = int(round((len(sorted_vals) - 1) * p))
    return sorted_vals[idx]


def run_probe(args: argparse.Namespace) -> int:
    root = Path(__file__).resolve().parents[1]
    jar = (root / args.jar).resolve()
    if not jar.exists():
        raise FileNotFoundError(f"Jar not found: {jar}")

    scid = f"{random.randint(1, 0x7FFFFFFF):08x}"
    socket_name = f"scrcpy_{scid}"
    server_cmd = build_server_command(args, scid)

    print(f"[probe] serial={args.serial or '(default)'} mode={'amlogic' if args.amlogic else 'normal'}")
    print(f"[probe] push {jar} -> {args.remote_jar}")
    run_cmd(adb_cmd(args.serial, "push", str(jar), args.remote_jar))
    run_cmd(adb_cmd(args.serial, "forward", "--remove", f"tcp:{args.port}"), check=False)
    run_cmd(adb_cmd(args.serial, "forward", f"tcp:{args.port}", f"localabstract:{socket_name}"))
    run_cmd(adb_cmd(args.serial, "shell", "command -v pkill >/dev/null 2>&1 && pkill -f com.genymobile.scrcpy.Server || true"), check=False)

    proc = subprocess.Popen(
        adb_cmd(args.serial, "shell", server_cmd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    output_stop = threading.Event()
    output_thread = threading.Thread(target=stream_server_output, args=(proc, output_stop), daemon=True)
    output_thread.start()

    sock: Optional[socket.socket] = None
    stats = Stats()
    lag_window: deque[float] = deque(maxlen=200)
    pts_offset_us: Optional[int] = None
    begin = time.time()
    next_report = begin + 1.0

    try:
        sock, dummy = connect_video_with_dummy(args.port, args.startup_timeout)
        print(f"[probe] dummy={dummy}")

        device_meta = recv_exact(sock, DEVICE_NAME_FIELD_LENGTH, 5.0)
        if not device_meta:
            raise RuntimeError("Failed to receive device meta")
        device_name = device_meta.split(b"\x00", 1)[0].decode("utf-8", errors="replace")
        print(f"[probe] device={device_name}")

        header = recv_exact(sock, 12, 5.0)
        if not header:
            raise RuntimeError("Failed to receive video header")
        codec_id, width, height = struct.unpack(">III", header)
        codec_text = codec_id.to_bytes(4, byteorder="big", signed=False).decode("ascii", errors="replace")
        print(f"[probe] header codec=0x{codec_id:08x}({codec_text}) size={width}x{height}")

        while True:
            now = time.time()
            if now - begin >= args.duration:
                break

            packet_header = recv_exact(sock, 12, args.read_timeout)
            if packet_header is None:
                continue
            pts_flags, size = struct.unpack(">QI", packet_header)
            payload = recv_exact(sock, size, max(3.0, args.read_timeout))
            if payload is None:
                raise RuntimeError("Failed to receive packet payload")

            stats.packets += 1
            stats.bytes_total += size
            is_config = (pts_flags & PACKET_FLAG_CONFIG) != 0
            is_key = (pts_flags & PACKET_FLAG_KEY_FRAME) != 0
            if is_config:
                stats.config_packets += 1
            else:
                stats.media_packets += 1
                if is_key:
                    stats.key_packets += 1
                pts_us = int(pts_flags & ~(PACKET_FLAG_CONFIG | PACKET_FLAG_KEY_FRAME))
                host_us = time.monotonic_ns() // 1_000
                if pts_offset_us is None:
                    pts_offset_us = host_us - pts_us
                rel_lag_ms = (host_us - pts_us - pts_offset_us) / 1000.0
                lag_window.append(rel_lag_ms)
                stats.lag_samples += 1
                stats.lag_sum_ms += rel_lag_ms
                if stats.lag_samples == 1:
                    stats.lag_min_ms = rel_lag_ms
                    stats.lag_max_ms = rel_lag_ms
                else:
                    stats.lag_min_ms = min(stats.lag_min_ms, rel_lag_ms)
                    stats.lag_max_ms = max(stats.lag_max_ms, rel_lag_ms)

                if args.slow_read_ms > 0:
                    time.sleep(args.slow_read_ms / 1000.0)

            now = time.time()
            if now >= next_report:
                lag_list = list(lag_window)
                mean_ms = stats.lag_sum_ms / stats.lag_samples if stats.lag_samples else 0.0
                print(
                    "[probe] stats "
                    f"packets={stats.packets} media={stats.media_packets} cfg={stats.config_packets} key={stats.key_packets} "
                    f"lag_mean={mean_ms:.1f}ms lag_p95={percentile(lag_list, 0.95):.1f}ms "
                    f"lag_min={stats.lag_min_ms:.1f}ms lag_max={stats.lag_max_ms:.1f}ms"
                )
                next_report = now + 1.0

        lag_list = list(lag_window)
        mean_ms = stats.lag_sum_ms / stats.lag_samples if stats.lag_samples else 0.0
        print(
            "[probe] final "
            f"packets={stats.packets} media={stats.media_packets} cfg={stats.config_packets} key={stats.key_packets} "
            f"bytes={stats.bytes_total} lag_mean={mean_ms:.1f}ms lag_p95={percentile(lag_list, 0.95):.1f}ms "
            f"lag_min={stats.lag_min_ms:.1f}ms lag_max={stats.lag_max_ms:.1f}ms"
        )
        return 0
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        output_stop.set()
        run_cmd(adb_cmd(args.serial, "shell", "command -v pkill >/dev/null 2>&1 && pkill -f com.genymobile.scrcpy.Server || true"), check=False)
        run_cmd(adb_cmd(args.serial, "forward", "--remove", f"tcp:{args.port}"), check=False)
        if proc.poll() is None:
            try:
                proc.terminate()
                proc.wait(timeout=2.0)
            except Exception:
                try:
                    proc.kill()
                except Exception:
                    pass


def main() -> int:
    ensure_utf8_stdio()
    args = parse_args()
    try:
        return run_probe(args)
    except KeyboardInterrupt:
        print("[probe] interrupted")
        return 130
    except Exception as exc:
        print(f"[probe] ERROR: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
