# -*- coding: utf-8 -*-
"""借用 HGTV 1.3.8.13 的签名器（H0.L3.h）给一批请求签名。

输入 sign_requests.json：[{base, query(保序对象), body_b64(可空), method}]
输出 sign_results.json：同序追加 url/headers/method/body_b64，或 err。
用法: python rpc_sign.py   （HGTV 须已稳定运行并过了友盟初始化）
"""
import json
import os
import sys
import time

import frida

ADB = os.environ.get("ADB", "adb")   # 或设 ADB=<SDK>/platform-tools/adb.exe
HERE = os.path.dirname(os.path.abspath(__file__))
BUNDLE = os.path.join(HERE, "agent-hgtv-m0.bundle.js")


def hgtv_pid():
    import subprocess
    out = subprocess.run([ADB, "-s", "emulator-5554", "shell", "pidof", "com.hgtv.star"], capture_output=True, text=True).stdout.strip()
    return int(out.split()[0]) if out and out.split()[0].isdigit() else None


def main():
    reqs = json.load(open(os.path.join(HERE, "sign_requests.json"), encoding="utf-8"))
    pid = hgtv_pid()
    if pid is None:
        print("[!] HGTV 未运行"); sys.exit(1)
    dev = frida.get_device_manager().add_remote_device("127.0.0.1:27042")
    session = dev.attach(pid)
    script = session.create_script(open(BUNDLE, encoding="utf-8").read())
    script.on("message", lambda m, d: print("[msg]", str(m)[:300], flush=True))
    script.load()
    time.sleep(1)
    results = []
    for i, r in enumerate(reqs):
        out = dict(r)
        try:
            signed = script.exports_sync.sign(r["base"], json.dumps(r["query"], ensure_ascii=False), r.get("body_b64"), r.get("method"), bool(r.get("use_live")))
            if "err" in signed:
                out["err"] = signed["err"]
            else:
                out.update(signed)
        except Exception as e:  # noqa: BLE001
            out["err"] = repr(e)
        print(f"[{i}] {r['method']} {r['base'].split('/', 3)[-1]} → {'ERR ' + out['err'][:200] if 'err' in out else 'url=' + str(len(out['url'])) + ' hdrs=' + str(len(out['headers']))}", flush=True)
        results.append(out)
    json.dump(results, open(os.path.join(HERE, "sign_results.json"), "w", encoding="utf-8"), ensure_ascii=False)


if __name__ == "__main__":
    main()
