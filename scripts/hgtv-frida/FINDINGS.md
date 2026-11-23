# 红果播放签名排查结论（2026-11-23 ～ 11-24）

**定案：我方三个播放签名里 `x-medusa` 的实现是错的，已于 11-23 晚整体替换为 HGTV 1.3.8.12 的精确移植
（`HongguoMedusa`），离线对拍与 HGTV 真实签名、官方 App 真实签名全等。** 另发现并修复第二个拒因：
`preload` 路由的 query 没有调谐 medusa selector。

> **状态：已真网验证通过（2026-11-24 00:02）。** 我方新实现以 `preload` 形态发出的请求被服务端放行：
> HTTP 200 / 26.8KB / code=0 / 带片源。真网总共只打了这 1 枪（另 1 次设备注册）。
> 尚未发版——线上用户要等带新签名的 APK 发布才能恢复，详见「发版注意」。

## 真网验证（2026-11-24）

用 `HgMedusaVerifyProbeTest`（单枪探针）验证，走 App `preload` 路由的真实请求构造
（同一个 `preloadQuery` / `preloadBody` / `gzipDeflate` / 签名 / 头集 / HTTP 客户端），绕过
`fetchPreloadData` 的「空响应升级 device_level 重发」与熔断，严格 1 枪。

| 项 | 值 |
|---|---|
| 时间 / x-khronos | 2026-11-24 00:02:45 / `1795449765` |
| 身份 | 探针临时注册的新设备（非 HGTV 的身份，也非线上用户的持久化身份） |
| 版本 / 路由 | `74532 / 7.4.5.32`，内置默认档 `["preload", "v5tab"]` |
| 请求 | `POST /novel/player/multi_video_model/preload/v1`，21 个头，selector=12，gzip body 236 字节 |
| 响应 | **HTTP 200，26810 字节，404ms**，`{"BaseResp":{"StatusCode":0,"StatusMessage":"success"},"code":0,"data":{"<vid>":{…"video_model":…}}}` |
| 请求自检 | 发出后用 `scripts/hgtv-sign/verify_shot.py` 解密核对：PASS |

**dry-run 拦下了一个离线对拍没暴露的 bug**：发枪前先让探针只构造请求、不发，落盘后用解密器核对，
发现 `x-medusa` 里的摘要对不上实际要发送的 query。原因是 `HongguoMedusa.signWith` 照 HGTV
「收完整 URL、内部截 `?` 之后」的语义写的，而生产路径 `HongguoPlaybackV5.sign` 传进来的是**纯 query**
（不含 `?`），`substringAfter('?')` 取到空串——等于给空 query 签名。离线金标准喂的都是 HGTV 的完整 URL，
所以对拍全绿也没发现。没有这一步，那一枪必败。修法：签名入口只收纯 query；补回归测试
`HongguoMedusaTest.productionPathSignsTheQueryItSends`（走完整生产入口，解出 protobuf 断言覆盖的
就是要发送的 query 与 body）。同一轮 dry-run 还查出探针自己漏了 gzip（App 实际先 gzip 再签再发）。

**由此定下的规矩**：签名类改动在离线对拍全等之后、真网之前，必须先 dry-run 落盘并用
`verify_shot.py` 解密核对将发出的请求。

**这次验证没覆盖的**：

- `v5tab` 形态（三个签名头的 GET）在严格期能否放行——仍是未知，只作次选。
- 只验证了「新注册设备 + 我方新签名」这一种组合，且只打了 1 枪；长时间、多设备的稳定性未观察。
- 用的是 JVM 探针而非装机的 App。签名、请求构造、HTTP 客户端与 App 是同一份代码，但 App 内
  `fetchPreloadData` 的完整链路（含空响应重发、熔断、解析）没有在真机上点开一集验证过。

## 两个拒因

### 1. x-medusa 实现不等价（主因）

此前用的是 openhongguo PR#13（Go 版）的简化翻译。用解密器把 HGTV 的真实 medusa 拆开后逐层对比，
它至少三层与真实现不同：

| 层 | PR#13 Go 版 | HGTV / 官方 SDK |
|---|---|---|
| 摘要 | 自造的轮函数，计数偏移 `0x9d4a97c2` | SHA-256 骨架变体（K1 selector-12：索引表 `{3,5,2,7,1,0,4,6}`、计数偏移 66、96 轮、轮常量下标从 66 起） |
| protobuf 消息体 | 约 15 个字段 | schema 驱动的完整消息（约 20 个主字段 + 环境子消息 + 40 字段设备子消息 + 状态 JSON） |
| 加密封装 | 内层包布局与位映射不同 | `[C(signKey)] + le64(check) + 反序异或消息 + rnd 高 2 字节`，位映射 + AES 变种载波 |

服务端按请求自带的 query / body / 时间戳重算摘要比对，不等价即拒（HTTP 200 空 body）。

### 2. preload 路由的 query 没调谐 selector（次因，修好主因后仍会 15/16 被拒）

medusa 的摘要分支由 `C(SM3(query) + MD5(body) + le32(ts)) & 15` 选定。**官方 SDK 16 个分支都实现，
selector 在 0~15 均匀随机**（2055 条官方样本实证）；HGTV 与我方只移植了 12 号分支，服务端按请求
自带的 selector 选分支验签。所以 query 必须通过换 `_rticket` 的毫秒偏移调谐到 12。
`v5tab` 路由一直有调谐；`preload` 路由此前用 Argus 通道的 `signRequest` 出 query，没有调谐。

## 证据（离线对拍）

工具链在 `scripts/hgtv-sign/`（解密器、参考实现、金标准生成、落盘请求核对；README 有复现步骤与踩坑）。

| 对拍 | 样本来源 | 结果 |
|---|---|---|
| Python 参考实现重建 protobuf | frida 抓的 HGTV 1.3.8.12 签名 48 组 | 逐字节 48/48 |
| Python 参考实现 x-medusa 全串 | 同上 | 48/48 |
| Kotlin `HongguoMedusa` protobuf + x-medusa | 同上（金标准回放） | **48/48** |
| Kotlin 摘要 vs 官方 App SDK | 11-13 抓的官方请求，selector 落 12 且 URL 精确的 | **107/107** |
| gorgon 全串 | HGTV 48 组 | 48/48 |
| helios 全串 | HGTV 48 组 | 48/48 |
| 合成向量（虚构身份，入库） | 参考实现产出，覆盖 ts&3 四个分支 | 4/4 |

其他实证：

- 官方 App 的 2057 条 medusa 用同一套密钥解开了 2055 条 → HGTV 与官方 SDK 共用签名密钥与加密层，
  protobuf 结构同构。
- 服务端不校验 protobuf 里设备状态字段的「真实性」：官方是真值、HGTV 是假值，两边都收。
  卡的只是摘要与请求自洽。
- HGTV 1.3.8.12 的 48 条请求 selector 全部为 12（它的 `_rticket` 调谐确实落在 12）。

## 已推翻的假说（勿重走）

- **「服务端在 POST/GET 形态间切换，`video_tab` 是唯一放行通道」**：错。`v5tab` 11-23 上午放行的 31 次
  发生在宽松期（错误的 medusa 也放行），不构成严格期可用的证据。
- **host 维度**：HGTV 用的就是 sinfonlineb。
- **设备身份 / 注册方式 / Cookie**：HGTV 的老设备身份配我方旧 medusa 照样空。
- **「PR#13 Go 版只差 protobuf 字段」**：错，摘要与加密封装也不等价。

## 代码改动

- 新增 `HongguoMedusa.kt`：medusa 的精确移植（摘要 / protobuf / 加密封装 / 签名器状态）。签名入口
  （`Signer.sign` / `signWith`）**只收纯 query**，调用方给最终发出的原样。
- `HongguoPlaybackV5.kt`：只保留 query 编码、selector 调谐、V5 头集组装；旧的简化实现整体移除，
  断言它的 `HongguoPlaybackV5VectorTest`、`HongguoV5HashLayersTest`、`v5_vectors.json` 一并移除。
- `HongguoPlay.kt`：`preload` 路由改走 V5 签名（`v5SignedPost`），头集对齐 HGTV V5 分支（21 个头同名同序）。
  为单枪探针把 query 构造、请求构造、gzip 提成 internal 函数（`preloadQuery` / `buildPreloadRequest` /
  `gzipDeflate`），行为不变。
- 内置默认路由 `v5tab` → `preload`（`HongguoNetProfiles`）；**仓库 `version.json` 的 `hg_net.play_routes`
  没有改，仍是 `["v5tab"]`**，原因见下。
- 测试：`HongguoMedusaTest`（合成向量 4 组 + HGTV 金标准回放 + 官方样本摘要 + 生产入口回归）、
  `HongguoSignDiffTest`（gorgon / helios 对 HGTV 全串、HGTV 请求 selector 全在 12）、
  `HgMedusaVerifyProbeTest`（单枪真网探针，两段式）。全量单测（排除真网探针）415 项全绿。

## 发版注意

- **必须发新 APK 才能救线上用户**：老版本里的 medusa 实现仍是错的，只推 `version.json` 换路由没用。
- **发版时把 `version.json` 的 `hg_net.play_routes` 改成 `["preload", "v5tab"]`**，与新 APK 一起提交。
  现在不改是因为热更档对老版本同样生效：一旦推送，老版本会切到它自己的旧 `preload` 实现（同样被拒，
  且空响应会升级 `device_level` 再发一枪），比现状更糟；而不改的话，新 APK 的内置默认档又会被热更档
  覆盖回 `v5tab`。所以两者必须同时生效。
- 老版本在新 APK 发布后的过渡期里仍会被切到旧 `preload`（每次尝试播放 2 枪、照样失败），直到用户升级。
- `v5tab` 形态在严格期能否放行未验证，只作次选。

## 复现方法（下午建的 frida 工具，保留）

工具在 `scripts/hgtv-frida/`（agent + 驱动 + RPC 签名器），脱敏后的请求样本在
`sample/hgtv-13813-playback-requests.json`（query 键序 / header 顺序 / body 明文）。

1. 设备侧：`adb root` → `nohup /data/local/tmp/frida-x64 -D &` → `adb forward tcp:27042 tcp:27042`。
2. 启动 HGTV，**等 42 秒再 attach**（1.3.8.13 启动约 25 秒后在 `hgtv-startup-deferred` 线程初始化友盟，frida 在场时必 SIGSEGV）。
3. `./node_modules/.bin/esbuild agent-hgtv-m0.js --bundle --format=iife --outfile=agent-hgtv-m0.bundle.js`（需 `frida-java-bridge` + `buffer`）。
4. `python run_capture.py`：hook `H0.m0.p` 落盘 url/headers 保序/body；`python rpc_sign.py`：借 `H0.L3.h()` 签名。

## 探针纪律（当天血的教训）

- **被拒的枪同样计入限流**。17:10~17:38 打了二十多枪、大半被拒，之后连已知放行的参照都空，用户在同一网络下的
  **所有**红果客户端都不能播。连续 2~3 枪被拒立即停手，间隔 ≥25s，一轮只验一个变量。
- **签名类问题先做离线对拍**（解密器 + 金标准回放 + 官方样本），全等之前一发都不打。
- **对拍全等之后、真网之前，先 dry-run**：让探针只构造请求并落盘，用 `scripts/hgtv-sign/verify_shot.py`
  解密核对将发出的那一份。离线对拍验证的是「给定输入算得对」，dry-run 验证的是「生产路径把正确的
  输入喂了进去」——11-24 发枪前正是这一步拦下了纯 query 被截成空串的 bug。
- 全量单测必须用 `exclude-probes.init.gradle` 排除 `*Probe*`/`*Smoke*`。
