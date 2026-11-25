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

## 红果配置（内置，不发版不改）

红果的签名版本档案、播放 host、取源路由、DNS 直连表 **2026-11-25 起全部内置常量**，云端热更已移除（`version.json` 不再有 `hg_versions`/`hg_net`/`hg_dns`，`ApkUpdater.ReleaseInfo` 也不再携带它们）。

原因：服务端每次收紧都是「域名 + 路径 + 参数集 + 画像 + UA」整套一起变，热更只能改其中几个字段、改不动形态；而且已发布的 Release 附件会把旧值推给新 APK（2026-11-25 播放全空，旧档 `["player"]` 把默认盖回已失效通道就是路径之一）。结论：**红果改版就发版**。

- 版本档案：`HongguoVersionProfiles`（default=74532/7.4.5.32，plan=72232/7.2.2.32）
- host / channel / 路由 / DNS：`HongguoNetProfiles`（默认路由 `video_model`，host `api5-normal-sinfonlineb.fqnovel.com`，`dnsTable()` 为内置 TNC 表）
- 取源形态（2026-11-25）：`video_model`=`POST https://reading.snssdk.com/novel/player/video_model/v1/`，21 键精简 query + `Redmi 25053RT47C`/Android 16/`os_api=36` 手机画像 + 系统默认 UA + `device_id/iid/cdid` 用真实注册身份 + `biz_param.device_level=2` gzip body + 无 Cookie；签名仍是 V5 medusa（field21=312，377 才被拒）。旧形态 `player`/`preload`（fqnovel 域 + 35 键 + 真实形态画像 + 官方 App UA）自 2026-11-24 21:32 服务端配置切换后一律 200 空 body，仅留作代码备选
- **播放 query 的画像与注册分离**：注册报真实设备（`HongguoEnv`），`video_model` 播放 query 固定报官方手机画像（`Redmi 25053RT47C/16/36`）。两条是不同校验面；如实上报在模拟器/电视上反而被拒
- 预取：`playUrlBatch` 走 `video_model` 逐集，失败静默且**不计熔断**（`onEmptyBody(countBackoff=false)`），避免后台预取把前台起播锁 3 分钟
- 排障锚点（均走 AppLog）：`play video_model resp http= len=`、`play empty body headers [...]`（含 `tt-idc-switch`/`Server-Timing`/`X-Tt-Logid`，是服务端拒绝时唯一线索）、`play empty body path= … cooldown=`、`preload upcoming ok N/M`、`device register failed`
- 200 空 body 排查顺序：①先看响应头 `Server-Timing` 是否有值（有=业务层处理，不是网络/CDN）与 `tt-idc-switch` 时间点；②同期 feed/详情是否正常（正常=不是 IP 限流）；③按上面形态逐项核对（域名/路径/参数集/画像/UA/device_level 缺一不可）。真网探针仍需 dry-run + `scripts/hgtv-sign/verify_shot.py` 核对，全量单测须用 init 脚本排除 `*Probe*`/`*Smoke*`
