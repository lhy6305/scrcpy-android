#!/usr/bin/env python3
"""
Probe scrcpy control-channel exec-shell extension (TYPE=18 -> TYPE=3 result).

This script:
1) pushes latest server/agent jars
2) starts scrcpy-server
3) opens video/audio/control sockets via adb forward
4) sends control exec-shell command
5) receives and prints shell result
"""

from __future__ import annotations

import argparse
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional, Sequence, Tuple


def ensure_utf8_stdio() -> None:
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except ValueError:
                pass


def log(msg: str) -> None:
    print(f"[control-probe] {msg}", flush=True)


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
    for raw in cp.stdout.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    raise RuntimeError("no adb device found")


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


def read_be_u16(sock: socket.socket) -> int:
    return struct.unpack(">H", read_exact(sock, 2))[0]


def read_be_u32(sock: socket.socket) -> int:
    return struct.unpack(">I", read_exact(sock, 4))[0]


def read_be_u64(sock: socket.socket) -> int:
    return struct.unpack(">Q", read_exact(sock, 8))[0]


def open_scrcpy_triplet(local_port: int, timeout_s: float = 8.0) -> Tuple[socket.socket, socket.socket, socket.socket]:
    socks = []
    try:
        for i in range(3):
            s = socket.create_connection(("127.0.0.1", local_port), timeout=timeout_s)
            s.settimeout(timeout_s)
            socks.append(s)
            log(f"socket[{i}] connected")

        video = socks[0]
        dummy = read_u8(video)
        name = read_exact(video, 64).split(b"\0", 1)[0].decode("utf-8", errors="replace")
        codec = read_be_u32(video)
        width = read_be_u32(video)
        height = read_be_u32(video)
        log(f"video dummy={dummy}, device='{name}', codec=0x{codec:08x}, size={width}x{height}")
        return socks[0], socks[1], socks[2]
    except Exception:
        for s in socks:
            try:
                s.close()
            except OSError:
                pass
        raise


def open_scrcpy_triplet_with_retry(local_port: int, attempts: int, interval_s: float, timeout_s: float = 8.0) -> Tuple[socket.socket, socket.socket, socket.socket]:
    last_err: Optional[Exception] = None
    for idx in range(1, attempts + 1):
        try:
            return open_scrcpy_triplet(local_port, timeout_s=timeout_s)
        except Exception as e:
            last_err = e
            log(f"open triplet attempt {idx}/{attempts} failed: {e}")
            if idx < attempts:
                time.sleep(interval_s)
    raise RuntimeError(f"failed to open scrcpy sockets after {attempts} attempts: {last_err}")


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


def close_sockets(*socks: socket.socket) -> None:
    for s in socks:
        try:
            s.close()
        except OSError:
            pass


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


def send_exec_shell(control_sock: socket.socket, sequence: int, command: str) -> None:
    raw = command.encode("utf-8", errors="replace")
    if len(raw) > ((1 << 18) - 13):
        raw = raw[: (1 << 18) - 13]
    packet = bytearray()
    packet.append(18)  # TYPE_EXEC_SHELL
    packet.extend(struct.pack(">Q", sequence))
    packet.extend(struct.pack(">I", len(raw)))
    packet.extend(raw)
    control_sock.sendall(packet)


def read_control_exec_shell_result(control_sock: socket.socket, expected_seq: int, timeout_s: float) -> str:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        msg_type = read_u8(control_sock)
        if msg_type == 0:  # clipboard
            n = read_be_u32(control_sock)
            _ = read_exact(control_sock, n)
            continue
        if msg_type == 1:  # ack clipboard
            _ = read_be_u64(control_sock)
            continue
        if msg_type == 2:  # uhid output
            _ = read_be_u16(control_sock)
            n = read_be_u16(control_sock)
            _ = read_exact(control_sock, n)
            continue
        if msg_type == 3:  # exec shell result
            seq = read_be_u64(control_sock)
            n = read_be_u32(control_sock)
            data = read_exact(control_sock, n)
            text = data.decode("utf-8", errors="replace")
            if seq == expected_seq:
                return text
            continue
        raise RuntimeError(f"unexpected control device message type: {msg_type}")
    raise RuntimeError("timeout waiting control exec-shell result")


def count_app_lines(output: str) -> int:
    return sum(1 for line in output.splitlines() if line.startswith("APP|"))


def main() -> int:
    ensure_utf8_stdio()
    parser = argparse.ArgumentParser(description="Probe scrcpy control exec-shell extension.")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="", help="adb serial; default auto detect")
    parser.add_argument("--local-port", type=int, default=27184)
    parser.add_argument("--scid", default="1234abcd")
    parser.add_argument("--bitrate", type=int, default=2_048_000)
    parser.add_argument("--max-size", type=int, default=1920)
    parser.add_argument("--timeout", type=float, default=15.0, help="seconds waiting for control result")
    parser.add_argument("--open-retries", type=int, default=20, help="socket open retry count")
    parser.add_argument("--open-retry-interval", type=float, default=0.3, help="seconds between socket open retries")
    args = parser.parse_args()

    script_path = Path(__file__).resolve()
    repo_root = script_path.parent.parent
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

    proc: Optional[subprocess.Popen] = None
    video = audio = control = None
    try:
        adb_cmd(args.adb, serial, ["shell", "pkill -f com.genymobile.scrcpy.Server || true"], timeout=10, check=False)
        adb_cmd(args.adb, serial, ["forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)
        adb_cmd(args.adb, serial, ["push", str(server_jar), "/data/local/tmp/scrcpy-server.jar"], timeout=60, check=True)
        adb_cmd(args.adb, serial, ["push", str(agent_jar), "/data/local/tmp/apkviewer-agent.jar"], timeout=60, check=True)

        server_cmd = build_server_cmd(scid_hex, args.bitrate, args.max_size)
        proc = start_server_process(args.adb, serial, server_cmd)
        time.sleep(0.5)
        adb_cmd(args.adb, serial, ["forward", f"tcp:{local_port}", f"localabstract:{socket_name}"], timeout=10, check=True)

        video, audio, control = open_scrcpy_triplet_with_retry(
            local_port,
            attempts=max(1, args.open_retries),
            interval_s=max(0.05, args.open_retry_interval),
        )
        seq = 1
        command = "CLASSPATH=/data/local/tmp/apkviewer-agent.jar app_process / org.las2mile.apkviewer.AgentMain list"
        log("send control exec-shell request")
        send_exec_shell(control, seq, command)
        output = read_control_exec_shell_result(control, seq, timeout_s=args.timeout)
        app_count = count_app_lines(output)
        log(f"control exec-shell app count: {app_count}")
        log("control exec-shell output (first 20 lines):")
        for i, line in enumerate(output.splitlines()[:20], start=1):
            log(f"{i:02d}: {line}")
        if app_count <= 0:
            raise RuntimeError("no APP lines in control exec-shell output")
        log("probe completed: OK")
        return 0
    except Exception as e:
        log(f"probe failed: {e}")
        return 2
    finally:
        close_sockets(*(s for s in (video, audio, control) if s is not None))
        stop_server_process(proc)
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
