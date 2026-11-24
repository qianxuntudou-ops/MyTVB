# MyBLBL 项目规则

## 通用规则

1. **提交代码必须由用户发起**：未经用户明确要求，不得执行 `git commit`、`git push` 等提交/推送操作。只读操作（status、diff、log）不受此限制。
2. **模拟器操作需经允许**：未经用户明确同意，不得在当前对话中操作模拟器（如 adb 安装/启动应用、模拟按键触摸、截屏、uiautomator dump 等）。
3. **定位问题**：不能瞎猜，如果无法定位，首选打入详细的日志进行定位。
4. **提交就在当前分支**：用户要求提交代码时，默认在当前所在分支提交。除非用户明确要求新建分支，否则不要擅自 `git checkout -b` 新建分支。
5. **终端统一使用 Git Bash**：执行终端命令时使用 Git Bash，不使用 `cmd` 或 PowerShell。

## 寻找 BUG 时的自我打分约束

接 bug 单时，先按下面这张记分卡自评，分够才动手，分不够先回去问报告的人要信息，不硬猜硬改。

### 记分卡（50 分起算）

**加分项：**
- 定位到具体行（如 store.ts 第 21 行）→ +20
- 只需要改 ≤2 个文件 → +10
- 有现成的同类修法可参考 → +10
- 纯内部小修，不碰核心机制 → +10

**减分项：**
- 要动连接/播放等核心机制 → -15
- 不知道是每次必现还是偶发 → -10

### 行动阈值

- **自评 ≥90 分**：有把握，自动上手修
- **自评 <90 分**：停手，先去问报告的人（复现条件、是否必现、影响范围等），信息补齐再动手

核心原则：**有把握就干，没把握就先问清楚，别瞎改核心逻辑。**

## 版本升级流程

当用户要求升级版本（如"升版本"、"版本改到1.2.0"等）时，自动执行以下步骤：

1. **修改版本号**：更新 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`
2. **生成更新日志**：
   - 运行 `git log 上一个tag..HEAD --oneline --no-merges` 获取所有提交
   - 筛选重大变更，按模块分类归纳
   - 用简洁的用户可读语言（非 commit message 原文）撰写更新说明
   - 将新版本内容追加到 `CHANGELOG.md` 顶部
3. **更新 `version.json`**（app 内更新检查的元数据，改仓库根目录的 `version.json`）：
   ```json
   { "version": "x.y.z", "apk": "MyTVB-vx.y.z-release.apk", "notes": "本版更新说明（可 \\n 换行）" }
   ```
   - `version` 不带 v 前缀，与 `versionName` 一致；`apk` 文件名必须与实际发版上传的 release APK 完全一致；`notes` 取本版 CHANGELOG 内容
   - `apk_mirrors`（可选，2026-11-24 起）：更新下载镜像热更，**发版时不用动**，缺省用 `ApkUpdater` 内置列表；平时发现镜像变慢只需改仓库 main 的 version.json 并提交，启动检查更新即生效，不用发版。格式 `["https://gh-proxy.com/", ...]`，最多 8 条，必须 https，非法项/全非法整体回落内置。除换源外不要动它
   - 发 GitHub Release 时，将 release APK 与这份 `version.json` **一并作为附件上传**（app 端检查链路：release 附件 → 仓库 main 的 version.json → api.github.com 三级容错，附件漏传时靠仓库文件兜住但 release 正文/附件仍是权威展示，v2.1.20 曾因漏传此附件导致全员「检查失败」）
4. **提交**：将 `build.gradle.kts`、`CHANGELOG.md` 和 `version.json` 一起提交，commit message 格式：`v{版本号}`
5. **发版后验证（强制）**：GitHub Release 网页发布完成后，**当次**运行 `bash scripts/verify_release_assets.sh`（直连不通时加代理参数，如 `bash scripts/verify_release_assets.sh http://127.0.0.1:7897`），脚本走 App 同款下载链路验证 latest release 的 version.json 可拉取且版本号与本地一致。报红=漏传或 Release 未生效，补传后重跑至 PASS 才算发版完成。网页上传附件靠人脑记清单并不可靠（v2.1.20 教训），此步是唯一的机器校验，不得跳过。

### CHANGELOG 格式规范

```markdown
## v{版本号} ({日期})

- 一句话功能点，面向用户
- 另一个功能点
```

- 每条用 `- ` 开头，一句话，简洁明了
- 面向普通用户，不要出现技术术语（如"session"、"-352风控"、"CTS偏移"）
- 合并同类项，不重复，控制在 10 条以内

## 红果配置热更（改版本/画像/host 不发版）

红果播放的版本档案、host、设备画像已支持远程热更，**优先改配置不发版**，改代码重发 APK 是最后手段。

### 热更字段（都在 `version.json`）

- `hg_versions.default` / `hg_versions.plan`：签名链版本档案（code+name 两档，plan=相关作品通道独立钉版）
- `hg_net`：**只能改 `play_host` / `channel` / `play_routes`**（2026-11-24 起）。**设备画像不可远程覆写**：`device_type`/`device_brand`/`os_version` 一律取运行设备真实值（`HongguoEnv`，与注册/详情接口同源），version.json 里写了也忽略——此前远程档固定 Redmi 25053RT47C/Android 16，让所有设备冒充同一台红米、与各自注册时上报的真机自相矛盾，而且 Release 附件的旧档还会把新 APK 一起带歪。其余环境参数（`os_api`/`resolution`/`dpi`/`rom_version`/`is_64_*`/`is_x86_device`）同样取 `HongguoEnv`，只有 `host_abi` 固定 `arm64-v8a`（官方无 x86_64 包，如实报才是异常值）
- `hg_net.play_routes`：取源路由（**2026-11-24 起只取首位、不做自动回退**：多形态串联会让服务端在几十秒内看到同一设备两种请求形态，比单路失败更像异常；11-23 两路一起回空也实证回退无用。切换通道就改首位）——`preload`=`preload/v1` POST（官方预取通道，2026-11-24 00:02 真网放行，默认首选）、`player`=`player/v1` POST（**官方起播正式通道**，`dr_scene=player`，**已真网验证，当前默认**）、`v5tab`=V5 签名 GET `video_tab`（果映TV 形态，严格期未确认，只作手动切换备选）。默认只有 `player` 一条（2026-11-24 10:53 真网单枪验证放行：HTTP 200 / 23.8KB / code=0 / video_model 可取；失败要退回 `preload` 就改首位）。2026-11-24 起 `preload`/`player` 两路的 query/body/头集**整体照抄官方 App 7.4.5.32 抓包**：query 35 个参数位同序（含官方那个空参数段 `&&`，补了 `cdid`/`is_64_runtime`/`is_64_device`/`is_x86_device`/`rom_version`/`klink_egdi`，去掉 `device_token`）、body 键序逐字节一致（`device_level=3`、`use_server_dns=false`）、gzip 用 level 9 + OS 0xFF（压出的字节 MD5 == 官方 `X-SS-STUB`）、头集收成官方那 14 个同名同序（`HongguoPlayRequestShapeTest` 固化）。签名仍是 `HongguoMedusa`（HGTV 1.3.8.12 精确移植，官方样本 selector==12 共 80 条摘要 80/80 全等）。空 body 视为被拒/限流，熔断后不再发请求（也不换端点）——**所以未验证的路由不能设为首位**。**注意热更档对老版本同样生效**：老版本不认识 `player`（会被过滤掉），只认 `preload`/`v5tab`

### 操作要点

1. **日常调整只改仓库 main 的 `version.json` 并提交**（App 每次启动检查更新即拉取生效；Release 附件里的 version.json 只在正式发版时同步，平时不用管）
2. **校验规则**（写非法值会被 App 静默忽略回落内置档）：host 必须 `https://xxx.fqnovel.com`；画像字段忽略（不参与校验）；版本 code 5~6 位数字；`play_routes` 只认 `v5tab`/`preload`/`player`，未知名与重复项剔除、全非法回落 `["player"]`；**只有首位生效**（不影响 host/画像采用）
3. **内置兜底档**在 `HongguoVersionProfiles`（BUILTIN_DEFAULT/PLAN）与 `HongguoNetProfiles`（BUILTIN）——若某组配置被验证长期稳定（如版本号永久切换），应把内置档一并同步，避免每次装机首轮都靠热更
4. 排障锚点（均走 AppLog，用户开「日志记录」导出即见）：`HgNetProfiles: restore/applyFrom … routes=`、`play preload resp http= len=`、`play video_tab ok/failed http= len=`、`play empty body path= … cooldown=`、`device register failed`。**200 空 body 有两个来源，先分清再动手**：①**签名被拒**——先做离线对拍，不要拿真网试错：`HongguoSignDiffTest`（gorgon / helios 对 HGTV 真实签名全字符串全等）+ `HongguoMedusaTest`（x-medusa 对 HGTV 金标准逐字节、对官方 App 样本的摘要），全等即排除签名层（工具与定案见 `scripts/hgtv-frida/FINDINGS.md`、`scripts/hgtv-sign/README.md`）。改签名相关的代码必须过这两组对拍。**query 必须经 `HongguoPlaybackV5.sign` 调谐到 medusa selector==12**：服务端按请求自带的 selector 选摘要分支，我方只移植了 12 号，没调谐的请求 15/16 被拒。②**IP 限流**——被拒请求累积后触发，同出口所有红果客户端一起挂，恢复 8~31 分钟以上不固定。App 已内置递增熔断（`HongguoPlay`，3→30 分钟），排障时别连打
5. 真网探针（JVM 单测跑真网）：`HgMedusaVerifyProbeTest`（**单枪**：走 App `preload` 路由的真实请求构造，绕过自动重发与熔断；默认 dry-run 只构造落盘，仓库根有 `.zcode/hgtv-sign/tools/FIRE` 才真发、发完即删）、`HgHgtvReplayProbeTest`（HGTV wire 请求原样重放 + 单头替换）、`HgRegProbeTest.probeOnce`（单枪，长时静默后探测限流是否解除）、`HgChannelProbeTest`/`HgBisectProbeTest`/`HgRegProbeTest.probeTabShapes`（逐端点、逐变量定位放行形态），旧家族 `HongguoExpandProbeTest` 与 `HongguoPlayProbeTest`。**枪间隔 ≥25s，被拒的枪同样计入限流，连续 2~3 枪被拒立即停手**（2026-11-23 教训：半小时二十多枪把用户出口打进限流，用户所有红果客户端都不能播；同日用户明确要求测试次数不得乱来）。**签名类问题的验收顺序：离线对拍全等 → dry-run 落盘并用 `scripts/hgtv-sign/verify_shot.py` 解密核对将发出的请求 → 最多 1 枪真网**（2026-11-23 晚：dry-run 解密核对拦下了一个离线对拍没暴露的 bug——签名入口把生产路径传入的纯 query 当成完整 URL 截取，取到空串；没有这一步那一枪必败），离线对拍不过一律不放枪；本地全量单测须用 init 脚本排除真网类（`filter { excludeTestsMatching "*Probe*"; excludeTestsMatching "*Smoke*" }`），否则一次全量就把本机 IP 打进限流
