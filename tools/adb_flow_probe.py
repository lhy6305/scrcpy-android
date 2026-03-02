#!/usr/bin/env python3
"""
Probe scrcpy stream-only reconnect and apkviewer-agent list behavior via adb.

This script intentionally uses `adb forward` for all client socket traffic.
"""

from __future__ import annotations

import argparse
import os
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path
from typing import List, Optional, Sequence, Tuple


def log(msg: str) -> None:
    print(f"[probe] {msg}", flush=True)


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
    lines = cp.stdout.splitlines()
    out: List[str] = []
    for line in lines:
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            out.append(parts[0])
    if not out:
        raise RuntimeError("no adb device found")
    return out[0]


def read_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RuntimeError(f"socket closed while reading {size} bytes")
        data.extend(chunk)
    return bytes(data)


def open_scrcpy_triplet(local_port: int, timeout_s: float = 8.0) -> Tuple[socket.socket, socket.socket, socket.socket]:
    sockets: List[socket.socket] = []
    try:
        for idx in range(3):
            s = socket.create_connection(("127.0.0.1", local_port), timeout=timeout_s)
            s.settimeout(timeout_s)
            sockets.append(s)
            log(f"socket[{idx}] connected")

        video = sockets[0]
        dummy = read_exact(video, 1)
        log(f"video dummy byte: {dummy[0]}")

        name_raw = read_exact(video, 64)
        name = name_raw.split(b"\0", 1)[0].decode("utf-8", errors="replace")
        codec = struct.unpack(">I", read_exact(video, 4))[0]
        width = struct.unpack(">I", read_exact(video, 4))[0]
        height = struct.unpack(">I", read_exact(video, 4))[0]
        log(f"video meta: device='{name}', codec=0x{codec:08x}, size={width}x{height}")
        return sockets[0], sockets[1], sockets[2]
    except Exception:
        for s in sockets:
            try:
                s.close()
            except OSError:
                pass
        raise


def open_scrcpy_triplet_with_retry(
    local_port: int, attempts: int, interval_s: float, timeout_s: float = 8.0
) -> Tuple[socket.socket, socket.socket, socket.socket]:
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


def close_sockets(*socks: socket.socket) -> None:
    for s in socks:
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


def build_detached_shell_command(command: str) -> str:
    trimmed = (command or "").strip()
    if trimmed.endswith(";"):
        trimmed = trimmed[:-1]
    escaped = trimmed.replace("'", "'\"'\"'")
    return (
        "("
        "(command -v nohup >/dev/null 2>&1 && nohup sh -c '"
        + escaped
        + "')"
        " || "
        "(command -v setsid >/dev/null 2>&1 && setsid sh -c '"
        + escaped
        + "')"
        " || "
        "("
        + trimmed
        + ")"
        ") >/dev/null 2>&1 < /dev/null &"
    )


def stop_server_process(proc: Optional[subprocess.Popen], grace_s: float = 1.0) -> None:
    if proc is None:
        return
    if proc.poll() is not None:
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
        f"max_size={max_size};"
    )


def find_artifact(repo_root: Path, rel_paths: Sequence[str]) -> Path:
    for rel in rel_paths:
        p = repo_root / rel
        if p.exists():
            return p
    raise FileNotFoundError("artifact not found in expected paths:\n" + "\n".join(rel_paths))


def count_agent_apps(output: str) -> int:
    count = 0
    for line in output.splitlines():
        if line.startswith("APP|"):
            count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe scrcpy reconnect and launcher agent behavior via adb.")
    parser.add_argument("--adb", default="adb", help="adb executable path")
    parser.add_argument("--serial", default="", help="adb serial (default: auto-detect first device)")
    parser.add_argument("--local-port", type=int, default=27183, help="local tcp port used for adb forward")
    parser.add_argument("--scid", default="1234abcd", help="scid hex string (8 chars max)")
    parser.add_argument("--bitrate", type=int, default=2_048_000)
    parser.add_argument("--max-size", type=int, default=1920)
    parser.add_argument("--skip-agent", action="store_true", help="skip apkviewer-agent checks")
    parser.add_argument(
        "--start-mode",
        choices=("detached", "foreground"),
        default="foreground",
        help="how to start scrcpy-server via adb shell",
    )
    parser.add_argument(
        "--start-wait",
        type=float,
        default=0.5,
        help="seconds to wait after each server start before first connect",
    )
    parser.add_argument(
        "--restart-retries",
        type=int,
        default=20,
        help="retry count for reconnect-with-restart socket open",
    )
    parser.add_argument(
        "--restart-retry-interval",
        type=float,
        default=0.3,
        help="seconds between reconnect-with-restart retries",
    )
    args = parser.parse_args()

    script_path = Path(__file__).resolve()
    repo_root = script_path.parent.parent
    serial = args.serial.strip() or detect_serial(args.adb)
    scid = int(args.scid, 16) & 0x7FFFFFFF
    scid_hex = f"{scid:08x}"
    socket_name = f"scrcpy_{scid_hex}"
    local_port = args.local_port

    log(f"repo: {repo_root}")
    log(f"serial: {serial}")
    log(f"local forward: tcp:{local_port} -> localabstract:{socket_name}")

    server_jar = find_artifact(
        repo_root,
        (
            "release-out/scrcpy-server.jar",
            "app/src/main/assets/scrcpy-server.jar",
        ),
    )
    agent_jar = find_artifact(
        repo_root,
        (
            "release-out/apkviewer-agent.jar",
            "app/src/main/assets/apkviewer-agent.jar",
        ),
    )
    log(f"server jar: {server_jar}")
    log(f"agent jar: {agent_jar}")

    server_proc_1: Optional[subprocess.Popen] = None
    server_proc_2: Optional[subprocess.Popen] = None
    v1 = a1 = c1 = None
    v2 = a2 = c2 = None
    start_ok = False
    reconnect_no_restart_ok = False
    reconnect_with_restart_ok = False

    try:
        adb_cmd(args.adb, serial, ["shell", "pkill -f com.genymobile.scrcpy.Server || true"], timeout=10, check=False)
        adb_cmd(args.adb, serial, ["forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)

        adb_cmd(args.adb, serial, ["push", str(server_jar), "/data/local/tmp/scrcpy-server.jar"], timeout=60, check=True)
        if not args.skip_agent:
            adb_cmd(args.adb, serial, ["push", str(agent_jar), "/data/local/tmp/apkviewer-agent.jar"], timeout=60, check=True)

        server_cmd = build_server_cmd(scid_hex, args.bitrate, args.max_size)

        # Initial deployment/start (equivalent to normal app flow after push).
        if args.start_mode == "foreground":
            server_proc_1 = start_server_process(args.adb, serial, server_cmd)
        else:
            detached_cmd = build_detached_shell_command(server_cmd)
            adb_cmd(args.adb, serial, ["shell", detached_cmd], timeout=20, check=True)
        time.sleep(max(0.0, args.start_wait))
        adb_cmd(args.adb, serial, ["forward", f"tcp:{local_port}", f"localabstract:{socket_name}"], timeout=10, check=True)
        v1, a1, c1 = open_scrcpy_triplet_with_retry(
            local_port=local_port,
            attempts=max(1, args.restart_retries),
            interval_s=max(0.05, args.restart_retry_interval),
        )
        start_ok = True
        log("initial stream open: OK")

        # Control test: reconnect without restarting server.
        close_sockets(v1, a1, c1)
        v1 = a1 = c1 = None
        adb_cmd(args.adb, serial, ["forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)
        adb_cmd(args.adb, serial, ["forward", f"tcp:{local_port}", f"localabstract:{socket_name}"], timeout=10, check=True)
        try:
            v1, a1, c1 = open_scrcpy_triplet(local_port)
            reconnect_no_restart_ok = True
            log("reconnect without server restart: OK")
            close_sockets(v1, a1, c1)
            v1 = a1 = c1 = None
        except Exception as e:
            log(f"reconnect without server restart: FAIL ({e})")

        # Simulate "reconnect stream only" with restart: no push, just restart server.
        adb_cmd(args.adb, serial, ["shell", "pkill -f com.genymobile.scrcpy.Server || true"], timeout=10, check=False)
        stop_server_process(server_proc_1)
        server_proc_1 = None
        time.sleep(0.5)

        if args.start_mode == "foreground":
            server_proc_2 = start_server_process(args.adb, serial, server_cmd)
        else:
            detached_cmd = build_detached_shell_command(server_cmd)
            adb_cmd(args.adb, serial, ["shell", detached_cmd], timeout=20, check=True)
        time.sleep(max(0.0, args.start_wait))
        adb_cmd(args.adb, serial, ["forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)
        adb_cmd(args.adb, serial, ["forward", f"tcp:{local_port}", f"localabstract:{socket_name}"], timeout=10, check=True)
        try:
            v2, a2, c2 = open_scrcpy_triplet_with_retry(
                local_port=local_port,
                attempts=max(1, args.restart_retries),
                interval_s=max(0.05, args.restart_retry_interval),
            )
            reconnect_with_restart_ok = True
            log("reconnect with server restart: OK")
        except Exception as e:
            log(f"reconnect with server restart: FAIL ({e})")

        if not args.skip_agent:
            out_ver = adb_cmd(
                args.adb,
                serial,
                [
                    "shell",
                    "CLASSPATH=/data/local/tmp/apkviewer-agent.jar app_process / org.las2mile.apkviewer.AgentMain --version",
                ],
                timeout=20,
                check=True,
            ).stdout
            out_list = adb_cmd(
                args.adb,
                serial,
                [
                    "shell",
                    "CLASSPATH=/data/local/tmp/apkviewer-agent.jar app_process / org.las2mile.apkviewer.AgentMain list",
                ],
                timeout=60,
                check=True,
            ).stdout
            app_count = count_agent_apps(out_list)
            log(f"agent version output: {out_ver.strip()}")
            log(f"agent list apps: {app_count}")

        log("probe completed")
        return 0
    except Exception as e:
        log(f"probe failed: {e}")
        return 2
    finally:
        close_sockets(*(s for s in (v1, a1, c1, v2, a2, c2) if s is not None))
        stop_server_process(server_proc_1)
        stop_server_process(server_proc_2)
        try:
            adb_cmd(args.adb, serial, ["forward", "--remove", f"tcp:{local_port}"], timeout=10, check=False)
        except Exception:
            pass
        try:
            adb_cmd(args.adb, serial, ["shell", "pkill -f com.genymobile.scrcpy.Server || true"], timeout=10, check=False)
        except Exception:
            pass
        log(
            "summary: "
            f"initial_stream_ok={start_ok}, "
            f"reconnect_no_restart_ok={reconnect_no_restart_ok}, "
            f"reconnect_with_restart_ok={reconnect_with_restart_ok}"
        )


if __name__ == "__main__":
    sys.exit(main())
