# -*- coding: utf-8 -*-
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

RECEIVER = "com.bbpillow.haku/.AskDebugReceiver"
ACTION_ASK = "com.bbpillow.haku.debug.ASK"
ACTION_UNLOAD = "com.bbpillow.haku.debug.ASK_UNLOAD"
DEFAULT_ADB = os.environ.get("ADB", "F:/AndroidSDK/platform-tools/adb.exe")
DEFAULT_SERIAL = "RFCW70ZSNDY"


def sh_quote(s: str) -> str:
    """POSIX single-quote for Android /system/bin/sh (`adb shell` joins args with spaces)."""
    return "'" + s.replace("'", "'\"'\"'") + "'"


def pct(vals: List[float], q: float) -> Optional[float]:
    if not vals:
        return None
    s = sorted(vals)
    idx = min(len(s) - 1, max(0, int(round((q / 100.0) * (len(s) - 1)))))
    return round(float(s[idx]), 2)


def foreground(adb: str, serial: str) -> None:
    """Android's cached-app freezer stalls a backgrounded app for minutes: keep the screen on and Haku in front."""
    for args in (["svc", "power", "stayon", "usb"], ["input", "keyevent", "KEYCODE_WAKEUP"],
                 ["am", "start", "-n", "com.bbpillow.haku/.MainActivity"]):
        subprocess.run([adb, "-s", serial, "shell", *args], capture_output=True)


def send_ask(adb: str, serial: str, qid: str, question: str, warm: bool) -> Optional[str]:
    cmd = [
        adb, "-s", serial, "shell",
        "am", "broadcast",
        "-n", RECEIVER,
        "-a", ACTION_ASK,
        "--es", "id", sh_quote(qid),
        "--es", "q", sh_quote(question),
    ]
    if warm:
        cmd += ["--ez", "warm", "true"]
    if os.environ.get("HAKU_SEMANTIC") == "1":  # opt into the semantic tier (off in the app until tuned)
        cmd += ["--ez", "semantic", "true"]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        return (proc.stderr or proc.stdout).strip() or f"adb exit {proc.returncode}"
    return None


def send_unload(adb: str, serial: str) -> None:
    subprocess.run(
        [adb, "-s", serial, "shell", "am", "broadcast", "-n", RECEIVER, "-a", ACTION_UNLOAD],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def wait_for_result(adb: str, serial: str, qid: str, timeout: float) -> Optional[Dict[str, Any]]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        proc = subprocess.run(
            [adb, "-s", serial, "logcat", "-d", "-s", "HakuAsk:I"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        for line in reversed(proc.stdout.splitlines()):
            if qid not in line:
                continue
            brace = line.find("{")
            if brace < 0:
                continue
            try:
                obj = json.loads(line[brace:])
                if obj.get("id") == qid:
                    return obj
            except ValueError:
                continue
        time.sleep(0.4)
    return None


def ask_one(adb: str, serial: str, qid: str, question: str, warm: bool, timeout: float) -> Dict[str, Any]:
    send_err = send_ask(adb, serial, qid, question, warm)
    if send_err is not None:
        return {"id": qid, "question": question, "error": send_err, "timeout": False}
    res = wait_for_result(adb, serial, qid, timeout)
    if res is None:
        return {"id": qid, "question": question, "error": "timeout", "timeout": True}
    res["timeout"] = False
    return res


def main() -> None:
    # ponytail: self-check quoting and percentile math at startup
    assert sh_quote('สวัสดี "Haku" it\'s me') == "'สวัสดี \"Haku\" it'\"'\"'s me'"
    assert pct([10, 20, 30, 40, 50], 50) == 30.0

    default_adb = DEFAULT_ADB
    default_q = Path(__file__).resolve().parent / "questions_th.txt"

    ap = argparse.ArgumentParser(description="Stress-test Haku Chat over adb")
    ap.add_argument("-s", "--serial", default=DEFAULT_SERIAL, help="adb device serial")
    ap.add_argument("--questions", default=str(default_q), help="path to UTF-8 questions file")
    ap.add_argument("--repeat", type=int, default=1, help="repeat question list N times")
    ap.add_argument("--warm", action="store_true", help="keep model loaded between questions")
    ap.add_argument("--limit", type=int, default=None, help="max questions to run")
    ap.add_argument("--timeout", type=float, default=120.0, help="per-question timeout in seconds")
    ap.add_argument("--out", default=None, help="output JSONL file path")
    args = ap.parse_args()
    foreground(os.environ.get("ADB", DEFAULT_ADB), args.serial)

    with open(args.questions, "r", encoding="utf-8") as f:
        base_questions = [line.strip() for line in f if line.strip() and not line.strip().startswith("#")]

    questions = base_questions * max(1, args.repeat)
    if args.limit is not None:
        questions = questions[: args.limit]

    run_tag = int(time.time() * 1000)
    results: List[Dict[str, Any]] = []
    out_file = open(args.out, "w", encoding="utf-8") if args.out else None

    try:
        for i, q in enumerate(questions, 1):
            qid = f"ask-{run_tag}-{i}"
            rec = ask_one(default_adb, args.serial, qid, q, args.warm, args.timeout)

            results.append(rec)
            if out_file:
                out_file.write(json.dumps(rec, ensure_ascii=False) + "\n")
                out_file.flush()

            src = rec.get("source") or ("TIMEOUT" if rec.get("timeout") else "ERR")
            ms = rec.get("totalMs")
            tps = rec.get("tokensPerSec")
            ans = (rec.get("answer") or rec.get("error") or "").replace("\n", " ")
            print(f"[{i}/{len(questions)}] {src} totalMs={ms} tok/s={tps} | {q} -> {ans[:80]}")
    finally:
        if out_file:
            out_file.close()
        if args.warm:
            send_unload(default_adb, args.serial)

    count = len(results)
    timeouts = sum(1 for r in results if r.get("timeout"))
    errors = sum(1 for r in results if not r.get("timeout") and r.get("error"))
    ok = count - timeouts - errors
    rejected = sum(1 for r in results if r.get("rejectedReasons"))
    model_n = sum(1 for r in results if r.get("source") == "MODEL")
    retr_n = sum(1 for r in results if r.get("source") == "RETRIEVAL")
    answered = model_n + retr_n
    model_pct = round(100.0 * model_n / answered, 1) if answered else 0.0
    retr_pct = round(100.0 * retr_n / answered, 1) if answered else 0.0

    ttfts = [float(r["ttftMs"]) for r in results if r.get("ttftMs") is not None]
    totals = [float(r["totalMs"]) for r in results if r.get("totalMs") is not None]
    tpss = [float(r["tokensPerSec"]) for r in results if r.get("tokensPerSec") is not None]
    heaps = [float(r["nativeHeapMb"]) for r in results if r.get("nativeHeapMb") is not None]
    temps = [float(r["batteryTempC"]) for r in results if r.get("batteryTempC") is not None]

    print("\n=== HakuAsk Stress Summary ===")
    print(f"count={count}  ok={ok}  errors={errors}  timeouts={timeouts}  rejected={rejected}")
    print(f"source: MODEL={model_n} ({model_pct}%)  RETRIEVAL={retr_n} ({retr_pct}%)")
    print(f"ttftMs:       p50={pct(ttfts, 50)}  p95={pct(ttfts, 95)}")
    print(f"totalMs:      p50={pct(totals, 50)}  p95={pct(totals, 95)}")
    print(f"tokensPerSec: p50={pct(tpss, 50)}  p95={pct(tpss, 95)}")
    print(f"max nativeHeapMb={max(heaps) if heaps else None}  max batteryTempC={max(temps) if temps else None}")


if __name__ == "__main__":
    main()
