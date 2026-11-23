# -*- coding: utf-8 -*-
"""HGTV 1.3.8.13 wire 请求抓取驱动（2026-11-23）。

attach 到 com.hgtv.star 并加载 agent-hgtv-m0.bundle.js，把 H0.m0.p 入口看到的每个 K3
（url/headers 保序/body base64/method）连同响应长度与头部落盘为 jsonl，供 JVM 探针原样重放。

用法: python run_capture.py [out.jsonl] [--state]
  --state  只打印一次域名池状态（V3.g()/lastGoodHost）后退出，不常驻
"""
import json
import os
import subprocess
import sys
import time

import frida

ADB = os.environ.get("ADB", "adb")   # 或设 ADB=<SDK>/platform-tools/adb.exe
HERE = os.path.dirname(os.path.abspath(__file__))
BUNDLE = os.path.join(HERE, "agent-hgtv-m0.bundle.js")
PKG = "com.hgtv.star"
STABLE_S = 42


def adb(*args):
    try:
        return subprocess.run([ADB, "-s", "emulator-5554", *args], capture_output=True, text=True, timeout=10).stdout
    except Exception as e:  # noqa: BLE001
        return ""


def hgtv_pid():
    out = adb("shell", "pidof", PKG).strip()
    return int(out.split()[0]) if out and out.split()[0].isdigit() else None


def window_ready():
    # 前台窗口已进入 HGTV 真实界面（过了启动页）——houdini 下启动期 attach 会 SIGSEGV 循环崩
    out = adb("shell", "dumpsys", "window")
    for ln in out.splitlines():
        if "mCurrentFocus" in ln and PKG in ln:
            return "Splash" not in ln
    return False


def wait_stable():
    last, since = None, None
    while True:
        pid = hgtv_pid()
        now = time.time()
        if pid is None:
            print("[*] 等待 HGTV 启动…", flush=True)
            last, since = None, None
            time.sleep(3)
            continue
        if pid != last:
            last, since = pid, now
            print(f"[*] pid={pid}，等它稳定且过启动页…", flush=True)
        # 1.3.8.13 启动约 25s 后在 hgtv-startup-deferred 线程延迟初始化友盟（crashsdk + ZID），
        # 带 frida 的进程里这段 native 初始化必 SIGSEGV（2026-11-23 17:47/17:05 两次实证）。
        # 必须等它初始化完再 attach：pid 稳定满 STABLE_S 秒才挂。
        if now - since >= STABLE_S and window_ready():
            return pid
        time.sleep(2)


def main():
    out_path = next((a for a in sys.argv[1:] if not a.startswith("--")), os.path.join(HERE, "reqs-13813.jsonl"))
    state_only = "--state" in sys.argv
    global STABLE_S
    if "--now" in sys.argv:
        STABLE_S = 3      # 进程早已稳定（过了友盟初始化）时直接挂
    dev = frida.get_device_manager().add_remote_device("127.0.0.1:27042")
    pid = wait_stable()
    session = dev.attach(pid)
    with open(BUNDLE, encoding="utf-8") as f:
        script = session.create_script(f.read())

    fh = open(out_path, "a", encoding="utf-8")
    counts = {"req": 0, "req_nonempty": 0}

    def on_message(msg, data):
        if msg.get("type") == "error":
            print("[!] script error:", msg.get("description"), msg.get("stack"), flush=True)
            return
        p = msg.get("payload")
        if not isinstance(p, dict):
            return
        fh.write(json.dumps(p, ensure_ascii=False) + "\n")
        fh.flush()
        t = p.get("type")
        if t == "req":
            counts["req"] += 1
            if p.get("respLen", -1) > 0:
                counts["req_nonempty"] += 1
            host = p["url"].split("/")[2] if "://" in p["url"] else "?"
            path = p["url"].split("://", 1)[-1].split("/", 1)[-1].split("?")[0] if "/" in p["url"].split("://", 1)[-1] else "?"
            print(f"[req #{counts['req']}] {p['method']} {host}/{path} body={len(p.get('body_b64') or '') } → len={p.get('respLen')} {p.get('costMs')}ms", flush=True)
        elif t in ("log", "failover-accept", "ready", "hook-error"):
            print(f"[{t}/{p.get('level', '')}] {p.get('line') or p.get('host') or p.get('err') or ''}", flush=True)

    script.on("message", on_message)
    script.load()
    print(f"[*] attached pid={pid}，落盘 {out_path}", flush=True)
    try:
        print("[*] state:", json.dumps(script.exports_sync.state(), ensure_ascii=False), flush=True)
    except Exception as e:  # noqa: BLE001
        print("[!] state 读取失败:", e, flush=True)
    if state_only:
        return
    while True:
        time.sleep(15)
        print(f"[*] 累计 req={counts['req']} 非空={counts['req_nonempty']}", flush=True)


if __name__ == "__main__":
    main()
