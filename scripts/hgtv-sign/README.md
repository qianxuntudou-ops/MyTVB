# 红果 x-medusa 离线对拍工具链（2026-11-23）

把 HGTV / 官方 App 的 `x-medusa` 签名拆开、复现、逐字节比对，全程离线、不向红果发任何请求。
产物是 `app/src/main/java/com/mytvb/feature/hongguo/net/HongguoMedusa.kt`（HGTV 1.3.8.12 的精确移植）。
排查结论与真网验证结果见 [`../hgtv-frida/FINDINGS.md`](../hgtv-frida/FINDINGS.md)。

## 文件（本目录，入库）

| 文件 | 作用 |
|---|---|
| `consts.json` | 从 HGTV dex 抽出的 medusa 常量表（S 盒 / 轮表 / 位映射）；由 `extract_consts.py` 产出 |
| `extract_consts.py` | 从 jadx 产物抽常量表 → `consts.json` |
| `dex_arrays.py` | 直接读 dex 字节码的 `fill-array-data`，绕开 jadx 的数组还原做交叉核对 |
| `medusa_dec.py` | **解密器**：逆向 HGTV `r0.E` 的封装层，把 x-medusa 拆回 protobuf 明文与全部内部状态 |
| `medusa_ref.py` | **参考实现**：正向签名（摘要 + protobuf 组包 + 序列化 + 加密），与 HGTV 48 组逐字节等价 |
| `decrypt_all.py` | 解密 frida 抓的 HGTV 样本 → 数据目录 `dec_samples.jsonl` |
| `replay_verify.py` | 用解密出的内部状态回放，确认参考实现与 HGTV 全等 |
| `gen_golden.py` | 生成 Kotlin 对拍金标准 → 数据目录 `golden.jsonl`（显式输入 + 期望输出） |
| `gen_synthetic.py` | 生成入库的合成向量 `app/src/test/resources/medusa_vectors.json`（虚构身份，含各阶段中间量） |
| `official_dec.py` / `official_verify.py` | 解密官方 App 的请求事件流，用服务端必收的 ground truth 反验摘要 |
| `verify_shot.py` | **发枪前自检**：解密 `HgMedusaVerifyProbeTest` 落盘的请求，核对签名覆盖的确实是要发送的那份 |

## 本地材料（不入库：含真实设备标识）

脚本按「仓库根 + 数据目录」找文件，数据目录默认 `.zcode/hgtv-sign/tools/`，可用环境变量 `HGTV_SIGN_DATA` 覆盖。

- `.zcode/hgtv-13812/`：HGTV 1.3.8.12（`dex/classes.dex`、`jadx-out/`）。签名类抽取件在 `.zcode/hgtv-sign/out_H0.*/`
  （`I1` 是 `abstract class` 不是包——Windows 大小写不敏感目录里与 UI 包 `i1` 合并过，真身在 `H0/I1.java`）
- `.zcode/hgtv-frida/sign_pairs.log`：frida 抓的 HGTV 签名对（`GORGON|…` / `HELIOS|…` / `MEDUSA|url|bodyB64|sec|out`）
- `.zcode/hongguo-api-capture/out/*/events-*.jsonl`：2026-11-13 抓的官方 App 请求事件流（2057 条带 x-medusa）
- 数据目录里的 `dec_samples.jsonl`、`golden.jsonl`、`official_dec.json`、`last_shot.json`、`probe_identity.json`
  都由本目录脚本或探针生成

## 对拍入口（全部离线）

```bash
python -B scripts/hgtv-sign/replay_verify.py     # 参考实现 vs HGTV：proto 48/48、x-medusa 48/48、tls 链 47/47
python -B scripts/hgtv-sign/official_verify.py   # 参考实现摘要 vs 官方 SDK：selector12 且 URL 精确的 107/107
```

Kotlin 侧（本地材料不存在时相应用例跳过；入库的合成向量始终执行）：

```bash
/g/MyTool/MyBLBL/gradlew -p /g/MyTool/MyBLBL :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
  --tests "com.mytvb.feature.hongguo.net.HongguoMedusaTest" \
  --tests "com.mytvb.feature.hongguo.net.HongguoSignDiffTest"
```

`testDebugUnitTest` 结果 UP-TO-DATE 时不重跑，必须先 clean，否则读到的是上一次的 XML。

## 真网验证：单枪流程

`HgMedusaVerifyProbeTest` 走 App `preload` 路由的真实请求构造，绕过自动重发与熔断。

```bash
# 1) dry-run：注册身份（之后缓存复用）并构造请求落盘，不发播放请求
/g/MyTool/MyBLBL/gradlew -p /g/MyTool/MyBLBL :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
  --tests "com.mytvb.feature.hongguo.net.HgMedusaVerifyProbeTest" -q
# 2) 离线核对将发出的请求；报 FAIL 就不要发
python -B scripts/hgtv-sign/verify_shot.py
# 3) 确认无误后放行，重跑即真发 1 枪（探针发完自动删除 FIRE，重跑不会再发）
touch .zcode/hgtv-sign/tools/FIRE
```

2026-11-24 00:02 的验证结果：HTTP 200 / 26.8KB / code=0 / 带片源。第 2) 步当时拦下了一个离线对拍没暴露的 bug
（签名入口把生产路径传入的纯 query 当成完整 URL 截取，取到空串），没有这一步那一枪必败。

## 协议要点（对拍定案）

- 摘要分支由 `C(SM3(query) + MD5(body) + le32(ts)) & 15` 选定（selector）。官方 SDK 16 个分支都实现，
  selector 在 0~15 均匀随机；HGTV 与我方只移植了 12 号，**query 必须调谐到 12**（换 `_rticket` 毫秒偏移）。
  服务端按请求自带的 selector 选分支验签，没调谐的请求 15/16 被拒。
- 三个内容签名都是确定性的，可全字符串对拍：gorgon（nonce 由 hook 抓下）、helios（随机种子明文在输出前 4 字节）、
  medusa（随机数在明文里，解密即得）。
- 服务端不校验 protobuf 里设备状态字段的「真实性」（官方真值与 HGTV 假值都收），卡的是摘要与请求自洽。
- HGTV 与官方 SDK 共用同一套签名密钥与加密层（官方样本 2055/2057 可用同一密钥解开）。

## 排查中踩过的坑

- **签名覆盖的就是发送的原样**：query 串、gzip 后的 body 字节、秒级时间戳，三者必须与请求里的一模一样。
  任何「先签名再变换」的环节（漏 gzip、URL 重新编码、拼错 query）都会让服务端重算的摘要对不上。
  离线对拍喂的是 HGTV 的完整 URL，生产路径喂的是纯 query——接口语义不一致时对拍发现不了，
  要靠「dry-run 落盘 → 解密核对」这一步。
- **jadx 会把 R8 优化后的判空三元式显示反**：`M0(47)` 的 `(i & 16) != 0 ? 0 : 1`、环境子消息
  「`startedAtSeconds == 0` 才写」两处都与运行时相反。可选字段的有无不靠读码，用样本里的明文定。
- dexdump 的 hex 是内存字节序：`dg[16:20] = 7c1a25c5` 指字节 `7c 1a 25 c5`，小端解读才是整数 `0xc5251a7c`。
- Java 的 `byte[]` 常量含负数（有符号），进旋转/移位前必须先归一为无符号。
- Kotlin 没有 `Byte xor Byte`（只有 Int / Long 的位运算）；`a to b xor c` 会先结合成 `Pair`，要加括号。
- 版本要对应：样本由哪个版本的 App 生成，就反编译哪个版本。GitHub release 的 `published_at` 是 UTC。
