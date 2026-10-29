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
   - 发 GitHub Release 时，将 release APK 与这份 `version.json` **一并作为附件上传**（缺失 `version.json` 时 app 端会退回 api.github.com 查询，国内直连经常失败）
4. **提交**：将 `build.gradle.kts`、`CHANGELOG.md` 和 `version.json` 一起提交，commit message 格式：`v{版本号}`

### CHANGELOG 格式规范

```markdown
## v{版本号} ({日期})

- 一句话功能点，面向用户
- 另一个功能点
```

- 每条用 `- ` 开头，一句话，简洁明了
- 面向普通用户，不要出现技术术语（如"session"、"-352风控"、"CTS偏移"）
- 合并同类项，不重复，控制在 10 条以内
