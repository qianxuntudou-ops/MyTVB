# 红果播放全挂排查结论（2026-11-23）

**根因：我方 `x-medusa` 签名头被服务端拒绝。** 我方实现（`HongguoPlaybackV5.medusa`，PR#13 Go 版移植的简化版）与服务端当前校验不再匹配；服务端 11-23 起收紧了对 POST 播放接口的 medusa 校验。

> 状态：用户 2026-11-23 决定**暂停修复，等参考工程（果映TV / aston314 / HGTV）修好后再对齐**。本文只记录已实证的事实与复现方法。

## 证据：同出口、同时刻、只换一个头

用 frida 抓 HGTV 1.3.8.13 的真实播放请求，在本机 JVM/OkHttp（与我方 App 同 TLS 栈）重放。17:14~17:26 期间**放行与被拒交替出现**，所以不是 IP 限流。

| 枪 | 内容 | 结果 |
|---|---|---|
| R0 | HGTV `POST /novel/player/multi_video_model/preload/v1` 原样重放 | 放行，315KB |
| R1 | HGTV `POST /novel/player/video_model/v1/` 原样重放 | 放行，26KB |
| H3 | 同一请求，只把 `x-helios` 换成我方实现 | 放行，314KB |
| H2' | 同一请求，只把 `x-medusa` 换成我方实现（按原始 query 串重算） | **空** |
| G1 | 同一请求，签名头全部换成我方实现 | 空 |
| T0 | 我方现行 preload 形态（新注册身份） | 空 |

HGTV 用的 host 就是我方内置的 `api5-normal-sinfonlineb.fqnovel.com`，出口 IP 相同。**结论：`x-helios` / `x-khronos` / `_rticket` 调谐正确，唯一被拒的是 `x-medusa`。**

## 我方 medusa 与 HGTV 的差异

- 种子部分一致：`SM3(query) + MD5(body) + le32(ts)` 经同一 V5 摘要（`HongguoPlaybackV5.hashV5` 与 HGTV `H0/I1.java` 的 `e()` 对齐）；body 两边都只以 MD5 进入种子。
- **差异在 protobuf 消息体**：我方约 15 个字段，其中 `21=377`、计数器全 1、`flags` 全零，缺 `lp/fl/sts/fkd/pd` 等设备状态串；HGTV 有 30+ 字段 + 设备环境子消息（`I1.java` 的 `map3` / `map2`）+ 状态 JSON（键 `cmr/cmr2/un_h/vpn/sts/kd/fkd/pd/lp/fl/dyn/do/tk`）。
- 具体服务端校验哪些字段**未定位**。参考工程修复后可直接 diff 它们的 medusa 实现。

## 已推翻的假说（勿重走）

- **「服务端在 POST/GET 形态间切换，`video_tab` 是唯一放行通道」**：错，是我方 medusa 被拒。
- **host 维度**：HGTV 用的就是 sinfonlineb。
- **设备身份 / 注册方式 / Cookie**：HGTV 的老设备身份配我方 medusa 照样空。
- **「Medusa 分支参数用错也放行」**：那是 11-23 上午宽松期的现象，不构成 medusa 正确的证据。

## 未确认

- `GET /reading/bookapi/video_tab/multi_video_model/v1` 是否已被服务端关闭：上午我方签名放行 31 次，17:32 起空（含 HGTV 老身份），但那两枪可能已在限流期。
- 借 HGTV 签名器（`H0.L3.h()`）签出的请求是否有效：M 系列六枪 17:37 后全空，同时混有「新建签名器实例状态为空」与「限流期」两个变量，整批作废。

## 复现方法

工具在 `scripts/hgtv-frida/`（agent + 驱动 + RPC 签名器），脱敏后的请求样本在 `sample/hgtv-13813-playback-requests.json`。

1. 设备侧：`adb root` → `nohup /data/local/tmp/frida-x64 -D &` → `adb forward tcp:27042 tcp:27042`。
2. 启动 HGTV，**等 42 秒再 attach**（1.3.8.13 启动约 25 秒后在 `hgtv-startup-deferred` 线程初始化友盟，frida 在场时必 SIGSEGV）。
3. `./node_modules/.bin/esbuild agent-hgtv-m0.js --bundle --format=iife --outfile=agent-hgtv-m0.bundle.js`（需 `frida-java-bridge` + `buffer`）。
4. `python run_capture.py`：hook `H0.m0.p` 落盘 url/headers 保序/body；`python rpc_sign.py`：借 `H0.L3.h()` 签名。
5. 重放与单头替换：JVM 单测 `HgHgtvReplayProbeTest`（`probeExactReplay` / `probeBisect2` 等）。

## 探针纪律（当天血的教训）

- **被拒的枪同样计入限流**。17:10~17:38 打了二十多枪、大半被拒，之后连已知放行的参照都空，用户在同一网络下的**所有**红果客户端都不能播。连续 2~3 枪被拒立即停手，间隔 ≥25s，一轮只验一个变量。
- 全量单测必须用 `exclude-probes.init.gradle` 排除 `*Probe*`/`*Smoke*`。
- 签名类问题优先做「金标准请求 + 单头替换」，不要凭形态矩阵猜。

## 工作区状态（未提交）

- 主代码：`HongguoPlay.kt` 取址路由化（`v5tab` 首选 / `preload` 次选）+ 空响应递增熔断（3→30 分钟）+ 日志改 `AppLog`；`HongguoNetProfiles.kt` 与 `version.json` 加热更 `hg_net.play_routes`；`HongguoRepository.kt` 重试链收敛；`HongguoPlayerActivity.kt` 预取 12→4 集；`HongguoDeviceRegister.kt` 注册失败补 W 级日志。
- 单测：`HongguoPlayTabEntryTest`（新）、`HongguoNetProfilesTest`（+3）；排除真网后全量 420/420 绿。
- 这些改动**不构成修复**（`v5tab` 首选有效性未确认），但路由化 / 熔断 / 日志本身无害，修 medusa 时可保留。
