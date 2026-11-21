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
- `hg_net`：播放 host + 设备画像四元组（`play_host`/`device_type`/`device_brand`/`os_version`/`channel`，三处同源自洽，改就整组改）

### 操作要点

1. **日常调整只改仓库 main 的 `version.json` 并提交**（App 每次启动检查更新即拉取生效；Release 附件里的 version.json 只在正式发版时同步，平时不用管）
2. **校验规则**（写非法值会被 App 静默忽略回落内置档）：host 必须 `https://xxx.fqnovel.com`；画像四元组任一非法整档丢弃；版本 code 5~6 位数字
3. **内置兜底档**在 `HongguoVersionProfiles`（BUILTIN_DEFAULT/PLAN）与 `HongguoNetProfiles`（BUILTIN）——若某组配置被验证长期稳定（如版本号永久切换），应把内置档一并同步，避免每次装机首轮都靠热更
4. 排障锚点：logcat `HgNetProfiles: restore/applyFrom`、`HgVersionProfiles`、`play preload/v1 vids=N level=`；200 空 body 全线出现时先跑 `HongguoExpandProbeTest.probeStability`（同形态×3 隔时重打）判是否封禁/灰度窗口（2026-11-21 实证窗口约 50 分钟自愈），别急着改代码
5. 真网探针家族 `HongguoExpandProbeTest`（detail/TNC/beat/host×版本矩阵/x3 形态/稳定性）与 `HongguoPlayProbeTest`：定位放行组合用 JVM 单测跑真网（几分钟），远快于装模拟器迭代
