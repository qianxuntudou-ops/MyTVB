# HGTV frida 抓包工具（红果 wire 请求）

2026-11-23 为排查红果播放全挂（见同目录 `FINDINGS.md`）建的工具链。目标是拿 HGTV 真实播放请求做「金标准」重放与单头替换，定位我方签名哪一项被服务端拒。

## 文件

| 文件 | 作用 |
|---|---|
| `agent-hgtv-m0.js` | frida agent：hook `H0.m0.p`（全 API 执行入口）落盘 `K3`（url/headers 保序/body/method）与响应长度；hook `H0.N1.b/c/d` 拿它自己的空响应与 failover 日志；hook `H0.V3.f` 看它接受了哪个 host；RPC `sign()` 借 `H0.L3.h()` 给任意请求签名；RPC `state()` 读域名池 |
| `run_capture.py` | 常驻抓包驱动，等 pid 稳定 42 秒后 attach，落盘 jsonl |
| `rpc_sign.py` | 读 `sign_requests.json` → 调 RPC 签名 → 写 `sign_results.json` |
| `exclude-probes.init.gradle` | 全量单测排除真网探针的 init 脚本（`--init-script` 传入） |
| `FINDINGS.md` | 2026-11-23 排查结论：根因、证据表、已推翻假说、复现步骤、探针纪律 |
| `sample/hgtv-13813-playback-requests.json` | 已脱敏的两条实测播放请求（query 键序 / header 顺序 / body 明文），签名与设备标识已抹除 |

## 用法

```bash
# 1) 设备侧（MuMu 等可 root 的模拟器）
adb root
adb shell "nohup /data/local/tmp/frida-x64 -D &"
adb forward tcp:27042 tcp:27042

# 2) 打包 agent（需 frida-java-bridge + buffer）
npm i frida-java-bridge buffer esbuild
./node_modules/.bin/esbuild agent-hgtv-m0.js --bundle --format=iife --outfile=agent-hgtv-m0.bundle.js

# 3) 启动 HGTV，等 42 秒后 attach（1.3.8.13 启动 25s 后初始化友盟，frida 在场必崩）
ADB=/path/to/adb.exe python run_capture.py reqs.jsonl
ADB=/path/to/adb.exe python rpc_sign.py

# 4) 全量单测排除真网探针
./gradlew :app:testDebugUnitTest --init-script scripts/hgtv-frida/exclude-probes.init.gradle
```

## hook 点（dex 真实名）

jadx 输出里的 `C0186m0` / `f1838a` / `p061v1` 都是显示用重命名，运行时要用 `H0.m0` / 字段 `a`。

- `H0.m0.p(m0, String url, K3, K1.z, boolean×3, Map, v1.l, int)`：`K3.a`=url、`K3.b`=headers（LinkedHashMap 保序）、`K3.c`=body（v5 模式已 gzip）、`K3.d`=method。收到「200 空 body」时会换 host 重签名后递归调自己，递归调用同样被捕获。
- `H0.L3.h(base, query, body, method, profile, gzip)`：签名入口，返回 `K3`。
- `H0.V3.g()` / `H0.V3.f(host)` / 静态字段 `V3.d`（`H0.X3`：`g`=apiHosts、`j`=lastGoodHost）：域名池，内置 6 个、TNC 下发扩到 9 个（`api5-normal-sinfonlinea/b/c`、`api5-normal-lf/lq/hl`、`api3-normal-lf/lq/hl`）。

## 坑

1. **1.3.8.13 启动 25 秒后初始化友盟**（`startup.umeng` 延迟任务），带 frida（jvmti）的进程里 UC crashsdk / ZID 的 native 初始化必 SIGSEGV。置空 `UMCrash.init` 只挡住第一处，第二处照崩——只能等它初始化完再 attach。
2. frida 17 的 `x.a.value` 取不到「字段与方法同名」的字段，须 `getDeclaredField` + `setAccessible(true)`；反射拿到的 `byte[]` 传 `Base64.encodeToString` 要显式 `.overload('[B','int')`。
3. 该包 `okhttp3` 只剩 4 个文件（已混淆），不能 hook `RealCall`。
4. MuMu 的 adb 默认是 `shell` 用户，attach 别的 App 须先 `adb root`（无 `su`）；用完 `adb unroot`。
5. **按键前必须校验前台包名**。崩溃后系统会把上一个任务顶回前台且无提示；我方红果首页是照 HGTV 复刻的，`uiautomator dump` 一定要先看 `package` 属性，否则会把键按到自己 App 上。
