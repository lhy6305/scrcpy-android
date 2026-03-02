#!/usr/bin/env python3
"""
Run latency_drop_probe.py with frame-drop ON/OFF and summarize latency metrics.

Any unknown arguments are passed through to latency_drop_probe.py.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path


FINAL_RE = re.compile(
    r"packets=(?P<packets>\d+)\s+"
    r"media=(?P<media>\d+)\s+"
    r"cfg=(?P<cfg>\d+)\s+"
    r"key=(?P<key>\d+)\s+"
    r"bytes=(?P<bytes>\d+)\s+"
    r"lag_mean=(?P<lag_mean>-?\d+(?:\.\d+)?)ms\s+"
    r"lag_p95=(?P<lag_p95>-?\d+(?:\.\d+)?)ms\s+"
    r"lag_min=(?P<lag_min>-?\d+(?:\.\d+)?)ms\s+"
    r"lag_max=(?P<lag_max>-?\d+(?:\.\d+)?)ms"
)


@dataclass
class Metrics:
    packets: int
    media: int
    cfg: int
    key: int
    bytes_total: int
    lag_mean: float
    lag_p95: float
    lag_min: float
    lag_max: float


@dataclass
class ProbeRun:
    drop_enabled: bool
    run_index: int
    metrics: Metrics


def ensure_utf8_stdio() -> None:
    # Windows shells often default to GBK; force UTF-8 for consistent parsing.
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except ValueError:
                pass


def parse_args() -> tuple[argparse.Namespace, list[str]]:
    parser = argparse.ArgumentParser(description="Compare scrcpy latency-drop ON/OFF behavior")
    parser.add_argument("--probe", default="tools/latency_drop_probe.py", help="path to latency_drop_probe.py")
    parser.add_argument("--python", default=sys.executable, help="python interpreter path")
    parser.add_argument("--runs", type=int, default=1, help="number of ON/OFF cycles")
    parser.add_argument("--port-base", type=int, default=27183, help="base local port for adb forward")
    parser.add_argument("--order", choices=["on-off", "off-on"], default="on-off", help="execution order inside each cycle")
    parser.add_argument("--sleep-between", type=float, default=1.0, help="delay seconds between probe runs")
    args, passthrough = parser.parse_known_args()
    if args.runs <= 0:
        raise ValueError("--runs must be > 0")
    return args, passthrough


def parse_final_metrics(lines: list[str]) -> Metrics:
    for line in reversed(lines):
        if "[probe] final" not in line:
            continue
        match = FINAL_RE.search(line)
        if not match:
            continue
        groups = match.groupdict()
        return Metrics(
            packets=int(groups["packets"]),
            media=int(groups["media"]),
            cfg=int(groups["cfg"]),
            key=int(groups["key"]),
            bytes_total=int(groups["bytes"]),
            lag_mean=float(groups["lag_mean"]),
            lag_p95=float(groups["lag_p95"]),
            lag_min=float(groups["lag_min"]),
            lag_max=float(groups["lag_max"]),
        )
    raise RuntimeError("Could not parse '[probe] final' metrics from probe output")


def run_probe(cmd: list[str], prefix: str) -> list[str]:
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    if proc.stdout is None:
        raise RuntimeError("Probe output pipe not available")

    lines: list[str] = []
    for line in proc.stdout:
        text = line.rstrip("\r\n")
        lines.append(text)
        print(f"[{prefix}] {text}")

    rc = proc.wait()
    if rc != 0:
        raise RuntimeError(f"Probe failed (rc={rc}): {' '.join(cmd)}")
    return lines


def average(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def summarize_mode(results: list[ProbeRun], drop_enabled: bool) -> dict[str, float]:
    mode = [r for r in results if r.drop_enabled == drop_enabled]
    return {
        "runs": float(len(mode)),
        "lag_mean": average([r.metrics.lag_mean for r in mode]),
        "lag_p95": average([r.metrics.lag_p95 for r in mode]),
        "lag_max": average([r.metrics.lag_max for r in mode]),
        "media": average([float(r.metrics.media) for r in mode]),
        "key": average([float(r.metrics.key) for r in mode]),
    }


def main() -> int:
    ensure_utf8_stdio()
    args, passthrough = parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    probe_path = Path(args.probe)
    if not probe_path.is_absolute():
        probe_path = (repo_root / probe_path).resolve()
    if not probe_path.exists():
        raise FileNotFoundError(f"Probe script not found: {probe_path}")

    mode_order = [True, False] if args.order == "on-off" else [False, True]
    results: list[ProbeRun] = []

    for cycle in range(args.runs):
        for mode_index, drop_enabled in enumerate(mode_order):
            port = args.port_base + cycle * 4 + mode_index
            drop_arg = "true" if drop_enabled else "false"
            cmd = [
                args.python,
                str(probe_path),
                "--port",
                str(port),
                "--video-latency-drop",
                drop_arg,
            ]
            cmd.extend(passthrough)

            mode_name = "drop_on" if drop_enabled else "drop_off"
            prefix = f"{mode_name}#{cycle + 1}"
            print(f"[compare] run {prefix}: {' '.join(cmd)}")
            lines = run_probe(cmd, prefix)
            metrics = parse_final_metrics(lines)
            results.append(ProbeRun(drop_enabled=drop_enabled, run_index=cycle + 1, metrics=metrics))

            if args.sleep_between > 0:
                time.sleep(args.sleep_between)

    on_summary = summarize_mode(results, True)
    off_summary = summarize_mode(results, False)

    print("[compare] summary")
    print(
        "[compare] drop_on "
        f"runs={int(on_summary['runs'])} lag_mean={on_summary['lag_mean']:.1f}ms "
        f"lag_p95={on_summary['lag_p95']:.1f}ms lag_max={on_summary['lag_max']:.1f}ms "
        f"media={on_summary['media']:.1f} key={on_summary['key']:.1f}"
    )
    print(
        "[compare] drop_off "
        f"runs={int(off_summary['runs'])} lag_mean={off_summary['lag_mean']:.1f}ms "
        f"lag_p95={off_summary['lag_p95']:.1f}ms lag_max={off_summary['lag_max']:.1f}ms "
        f"media={off_summary['media']:.1f} key={off_summary['key']:.1f}"
    )
    print(
        "[compare] delta_off_minus_on "
        f"lag_mean={off_summary['lag_mean'] - on_summary['lag_mean']:.1f}ms "
        f"lag_p95={off_summary['lag_p95'] - on_summary['lag_p95']:.1f}ms "
        f"lag_max={off_summary['lag_max'] - on_summary['lag_max']:.1f}ms "
        f"media={off_summary['media'] - on_summary['media']:.1f} "
        f"key={off_summary['key'] - on_summary['key']:.1f}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("[compare] interrupted")
        raise SystemExit(130)
    except Exception as exc:
        print(f"[compare] ERROR: {exc}")
        raise SystemExit(1)
