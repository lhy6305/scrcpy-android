#!/usr/bin/env python3
"""
Push latest jars and probe launcher/control behavior through raw scrcpy sockets.

It simulates:
1) launcher app-list queries via control exec-shell
2) rapid UI operations (touch move burst + key burst)
3) post-burst exec-shell liveness check

All outputs/log files are UTF-8 to avoid Windows GBK issues.
"""

from __future__ import annotations

import argparse
import socket
import statistics
import struct
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional, Sequence, Tuple


DEVICE_NAME_FIELD_LENGTH = 64
CONTROL_MSG_TYPE_INJECT_KEYCODE = 0
CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT = 2
CONTROL_MSG_TYPE_EXEC_SHELL = 18

DEVICE_MSG_TYPE_CLIPBOARD = 0
DEVICE_MSG_TYPE_ACK_CLIPBOARD = 1
DEVICE_MSG_TYPE_UHID_OUTPUT = 2
DEVICE_MSG_TYPE_EXEC_SHELL_RESULT = 3

KEY_ACTION_DOWN = 0
KEY_ACTION_UP = 1

MOTION_ACTION_DOWN = 0
MOTION_ACTION_UP = 1
MOTION_ACTION_MOVE = 2


def ensure_utf8_stdio() -> None:
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except ValueError:
                pass


def log(msg: str) -> None:
    print(f"[launcher-race-probe] {msg}", flush=True)


def run_cmd(cmd: Sequence[str], timeout: Optional[float] = None, check: bool = True) -> subprocess.CompletedProcess:
    log(f"run: {' '.join(cmd)}")
    cp = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )
    if cp.stdout.strip():
        log(f"stdout:\n{cp.stdout.rstrip()}")
    if cp.stderr.strip():
        log(f"stderr:\n{cp.stderr.rstrip()}")
    if check and cp.returncode != 0:
        raise RuntimeError(f"command failed ({cp.returncode}): {' '.join(cmd)}")
    return cp


def adb_cmd(adb: str, serial: str, args: Sequence[str], timeout: Optional[float] = None, check: bool = True) -> subprocess.CompletedProcess:
    return run_cmd([adb, "-s", serial, *args], timeout=timeout, check=check)


def detect_serial(adb: str) -> str:
    cp = run_cmd([adb, "devices"], timeout=10, check=True)
    serials = []
    for raw in cp.stdout.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    if not serials:
        raise RuntimeError("no adb device found")
    return serials[0]


def read_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RuntimeError(f"socket closed while reading {size} bytes")
        data.extend(chunk)
    return bytes(data)


def read_u8(sock: socket.socket) -> int:
    return read_exact(sock, 1)[0]


def read_u16_be(sock: socket.socket) -> int:
    return struct.unpack(">H", read_exact(sock, 2))[0]


def read_u32_be(sock: socket.socket) -> int:
    return struct.unpack(">I", read_exact(sock, 4))[0]


def read_u64_be(sock: socket.socket) -> int:
    return struct.unpack(">Q", read_exact(sock, 8))[0]


def open_scrcpy_triplet(local_port: int, timeout_s: float = 8.0) -> Tuple[socket.socket, socket.socket, socket.socket, Tuple[int, int]]:
    sockets = []
    try:
        for idx in range(3):
            s = socket.create_connection(("127.0.0.1", local_port), timeout=timeout_s)
            s.settimeout(timeout_s)
            sockets.append(s)
            log(f"socket[{idx}] connected")

        video = sockets[0]
        dummy = read_u8(video)
        name_raw = read_exact(video, DEVICE_NAME_FIELD_LENGTH)
        name = name_raw.split(b"\0", 1)[0].decode("utf-8", errors="replace")
        codec = read_u32_be(video)
        width = read_u32_be(video)
        height = read_u32_be(video)
        log(f"video meta: dummy={dummy}, device='{name}', codec=0x{codec:08x}, size={width}x{height}")
        return sockets[0], sockets[1], sockets[2], (width, height)
    except Exception:
        for s in sockets:
            try:
                s.close()
            except OSError:
                pass
        raise


def open_scrcpy_triplet_with_retry(local_port: int, attempts: int, interval_s: float) -> Tuple[socket.socket, socket.socket, socket.socket, Tuple[int, int]]:
    last_err: Optional[Exception] = None
    for idx in range(1, attempts + 1):
        try:
            return open_scrcpy_triplet(local_port)
        except Exception as exc:
            last_err = exc
            log(f"open triplet attempt {idx}/{attempts} failed: {exc}")
            if idx < attempts:
                time.sleep(interval_s)
    raise RuntimeError(f"failed to open scrcpy sockets after {attempts} attempts: {last_err}")


def close_sockets(*sockets: socket.socket) -> None:
    for s in sockets:
        try:
            s.close()
        except OSError:
            pass


def start_server_process(adb: str, serial: str, server_cmd: str) -> subprocess.Popen:
    cmd = [adb, "-s", serial, "shell", server_cmd]
    log(f"start server: {' '.join(cmd)}")
    return subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def stop_server_process(proc: Optional[subprocess.Popen], grace_s: float = 1.0) -> None:
    if proc is None or proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=grace_s)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=2.0)


def build_server_cmd(scid_hex: str, bitrate: int, max_size: int) -> str:
    return (
        "CLASSPATH=/data/local/tmp/scrcpy-server.jar "
        "app_process / com.genymobile.scrcpy.Server 3.3.4 "
        "log_level=info "
        f"scid={scid_hex} "
        "video=true "
        "video_source=display "
        "max_fps=30 "
        "audio=true "
        "video_codec_options=repeat-previous-frame-after:long=0 "
        f"video_bit_rate={bitrate} "
        "control=true "
        "tunnel_forward=true "
        "cleanup=false "
        f"max_size={max_size};"
    )


def send_key(control_sock: socket.socket, action: int, keycode: int) -> None:
    packet = struct.pack(">BBIII", CONTROL_MSG_TYPE_INJECT_KEYCODE, action, keycode, 0, 0)
    control_sock.sendall(packet)


def send_touch(control_sock: socket.socket, action: int, x: int, y: int, screen_w: int, screen_h: int, pressure: int) -> None:
    packet = struct.pack(
        ">BBqiiHHHII",
        CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT,
        action,
        -2,
        x,
        y,
        max(1, min(65535, screen_w)),
        max(1, min(65535, screen_h)),
        max(0, min(65535, pressure)),
        0,
        0,
    )
    control_sock.sendall(packet)


def send_exec_shell(control_sock: socket.socket, sequence: int, command: str) -> None:
    raw = command.encode("utf-8", errors="replace")
    max_len = (1 << 18) - 13
    if len(raw) > max_len:
        raw = raw[:max_len]
    packet = bytearray()
    packet.append(CONTROL_MSG_TYPE_EXEC_SHELL)
    packet.extend(struct.pack(">Q", sequence))
    packet.extend(struct.pack(">I", len(raw)))
    packet.extend(raw)
    control_sock.sendall(packet)


def read_exec_shell_result(control_sock: socket.socket, expected_seq: int, timeout_s: float) -> str:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        msg_type = read_u8(control_sock)
        if msg_type == DEVICE_MSG_TYPE_CLIPBOARD:
            n = read_u32_be(control_sock)
            _ = read_exact(control_sock, n)
            continue
        if msg_type == DEVICE_MSG_TYPE_ACK_CLIPBOARD:
            _ = read_u64_be(control_sock)
            continue
        if msg_type == DEVICE_MSG_TYPE_UHID_OUTPUT:
            _ = read_u16_be(control_sock)
            n = read_u16_be(control_sock)
            _ = read_exact(control_sock, n)
            continue
        if msg_type == DEVICE_MSG_TYPE_EXEC_SHELL_RESULT:
            seq = read_u64_be(control_sock)
            n = read_u32_be(control_sock)
            out = read_exact(control_sock, n).decode("utf-8", errors="replace")
            if seq == expected_seq:
                return out
            continue
        raise RuntimeError(f"unexpected control device msg type: {msg_type}")
    raise RuntimeError("timeout waiting exec-shell result")


def count_app_lines(output: str) -> int:
    return sum(1 for line in output.splitlines() if line.startswith("APP|"))


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    if len(values) == 1:
        return values[0]
    idx = int(round((len(values) - 1) * p))
    sorted_values = sorted(values)
    return sorted_values[idx]


def main() -> int:
    ensure_utf8_stdio()
    parser = argparse.ArgumentParser(description="Probe launcher list latency and rapid UI control behavior over scrcpy control channel")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="", help="adb serial (default auto detect)")
    parser.add_argument("--local-port", type=int, default=27186)
    parser.add_argument("--scid", default="1234abce")
    parser.add_argument("--bitrate", type=int, default=2_048_000)
    parser.add_argument("--max-size", type=int, default=1920)
    parser.add_argument("--list-repeat", type=int, default=3, help="number of launcher list calls")
    parser.add_argument("--touch-burst", type=int, default=400, help="number of touch MOVE messages to burst")
    parser.add_argument("--key-burst", type=int, default=20, help="number of quick key down/up pairs")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--open-retries", type=int, default=30)
    parser.add_argument("--open-retry-interval", type=float, default=0.3)
    parser.add_argument("--logcat-out", default="tools/launcher_race_probe_logcat.txt")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    serial = args.serial.strip() or detect_serial(args.adb)
    scid = int(args.scid, 16) & 0x7FFFFFFF
    scid_hex = f"{scid:08x}"
    socket_name = f"scrcpy_{scid_hex}"
    local_port = args.local_port

    server_jar = repo_root / "release-out" / "scrcpy-server.jar"
    agent_jar = repo_root / "release-out" / "apkviewer-agent.jar"
    if not server_jar.exists():
        raise FileNotFoundError(f"missing: {server_jar}")
    if not agent_jar.exists():
        raise FileNotFoundError(f"missing: {agent_jar}")

    logcat_path = (repo_root / args.logcat_out).resolve()
    logcat_path.parent.mkdir(parents=True, exist_ok=True)

    log(f"repo={repo_root}")
    log(f"serial={serial}")
    log(f"socket={socket_name}, local_port={local_port}")

    proc: Optional[subprocess.Popen] = None
    video = audio = control = None
    seq = 1
    timings_ms: list[float] = []
    app_count = 0
    burst_check_ok = False

    try:
        adb_cmd(args.adb, serial, ["logcat", "-c"], timeout=10, check=False)
        adb_cmd(args.adb, serial, ["shell", "pkill -f com.genymobile.scrcpy.Server || true"], timeout=10, check=False)
        adb_cmd(args.adb, serial, ["forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)
        adb_cmd(args.adb, serial, ["push", str(server_jar), "/data/local/tmp/scrcpy-server.jar"], timeout=60, check=True)
        adb_cmd(args.adb, serial, ["push", str(agent_jar), "/data/local/tmp/apkviewer-agent.jar"], timeout=60, check=True)

        server_cmd = build_server_cmd(scid_hex, args.bitrate, args.max_size)
        proc = start_server_process(args.adb, serial, server_cmd)
        time.sleep(0.5)

        adb_cmd(args.adb, serial, ["forward", f"tcp:{local_port}", f"localabstract:{socket_name}"], timeout=10, check=True)
        video, audio, control, size = open_scrcpy_triplet_with_retry(
            local_port=local_port,
            attempts=max(1, args.open_retries),
            interval_s=max(0.05, args.open_retry_interval),
        )
        screen_w, screen_h = size

        cmd_list = "CLASSPATH=/data/local/tmp/apkviewer-agent.jar app_process / org.las2mile.apkviewer.AgentMain list"
        repeat = max(1, args.list_repeat)
        for idx in range(repeat):
            t0 = time.perf_counter()
            send_exec_shell(control, seq, cmd_list)
            output = read_exec_shell_result(control, seq, timeout_s=args.timeout)
            cost_ms = (time.perf_counter() - t0) * 1000.0
            timings_ms.append(cost_ms)
            app_count = max(app_count, count_app_lines(output))
            log(f"list#{idx + 1}: {cost_ms:.1f}ms, app_count={count_app_lines(output)}")
            seq += 1

        x = max(1, screen_w // 2)
        y = max(1, screen_h // 2)
        send_touch(control, MOTION_ACTION_DOWN, x, y, screen_w, screen_h, 0xFFFF)
        move_count = max(1, args.touch_burst)
        for idx in range(move_count):
            mx = max(0, min(screen_w - 1, x + ((idx % 31) - 15)))
            my = max(0, min(screen_h - 1, y + (((idx // 31) % 31) - 15)))
            send_touch(control, MOTION_ACTION_MOVE, mx, my, screen_w, screen_h, 0xFFFF)
        send_touch(control, MOTION_ACTION_UP, x, y, screen_w, screen_h, 0x0000)
        log(f"touch burst sent: move_count={move_count}")

        key_count = max(0, args.key_burst)
        for idx in range(key_count):
            key = 4 if idx % 2 == 0 else 3
            send_key(control, KEY_ACTION_DOWN, key)
            send_key(control, KEY_ACTION_UP, key)
        log(f"key burst sent: key_pairs={key_count}")

        send_exec_shell(control, seq, "echo control_after_burst_ok")
        out = read_exec_shell_result(control, seq, timeout_s=args.timeout)
        burst_check_ok = "control_after_burst_ok" in out
        log(f"post-burst control check: {'OK' if burst_check_ok else 'FAIL'}")

        if timings_ms:
            mean_ms = statistics.mean(timings_ms)
            p50_ms = percentile(timings_ms, 0.50)
            p95_ms = percentile(timings_ms, 0.95)
            log(
                "launcher list latency: "
                f"calls={len(timings_ms)}, mean={mean_ms:.1f}ms, p50={p50_ms:.1f}ms, p95={p95_ms:.1f}ms, first={timings_ms[0]:.1f}ms"
            )

        if app_count <= 0:
            raise RuntimeError("no APP lines returned from launcher list")
        if not burst_check_ok:
            raise RuntimeError("control channel check after burst failed")

        log("probe completed: OK")
        return 0
    except Exception as exc:
        log(f"probe failed: {exc}")
        return 2
    finally:
        close_sockets(*(s for s in (video, audio, control) if s is not None))
        stop_server_process(proc)
        try:
            cp = adb_cmd(args.adb, serial, ["logcat", "-d", "-v", "threadtime", "-s", "scrcpy:*", "*:S"], timeout=15, check=False)
            logcat_path.write_text(cp.stdout or "", encoding="utf-8", errors="replace")
            log(f"logcat dumped -> {logcat_path}")
        except Exception as exc:
            log(f"WARN: failed to dump logcat: {exc}")
        try:
            adb_cmd(args.adb, serial, ["forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)
        except Exception:
            pass
        try:
            adb_cmd(args.adb, serial, ["shell", "pkill -f com.genymobile.scrcpy.Server || true"], timeout=10, check=False)
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())
