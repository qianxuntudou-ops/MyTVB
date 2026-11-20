# CCTV 直播备用频道源（未启用）

> 本目录是 **UTAO 云端（api.vonchange.com）频道表供给失败时的备用方案存档**。
> 当前**未启用**：代码中没有任何路径读取本目录，APK 也不打包这些文件（仅进 git）。
> 启用方法见下方「切换步骤」。

## 一、这是什么

2026-11-19 逆向「涛涛TV LIVE」APP（TVBox 魔改版，梆梆加固，产物存档见本机 `.trae/taotao-tv/`）
时提取出的完整直播源订阅。涛涛TV 的做法：把一份 TVBox 格式的频道 txt 伪装成 `log.png`
放在自己的 GitHub 仓库里当云端配置，APP 启动时拉取解析播放。我们把它原样抓下来存档，
作为 UTAO 第三方云端（utao 项目，`MarmotCloudUpdate.kt`）停服时的 Plan B。

- **本快照**：[backup-live-source-taotao.txt](backup-live-source-taotao.txt) —— 7879 行 / 约 7400 个地址 / 70 个分组（2026-11-19 抓取）
- **快照来源**：`https://raw.githubusercontent.com/taoBox2620/taoBox2620/java/log.png`（实测 2026-11-19 返回 200；注意 **main 分支的同名文件已被作者删除 404**，只有 java 分支还活着，随时可能也被删——所以才有本快照）
- **加速镜像**：`https://ghfast.top/https://raw.githubusercontent.com/taoBox2620/taoBox2620/java/log.png`（ghfast.top 为公共 GitHub 加速代理，非作者自营）

## 二、源格式（TVBox txt 两行式）

```
央卫电视,#genre#                          ← 分组行：组名 + ",#genre#"
CCTV-1,http://120.76.248.139/live/bfgd/4200000488.m3u8    ← 频道行：频道名,直链地址
CCTV-4K,http://223.167.230.46:4022/rtp/233.18.204.204:5140
```

同名频道可连续出现多行（即多线路，TVBox 里切线路就是切这批同名行）。

### 源构成与已知特性

| 构成 | 示例 | 特性 |
|---|---|---|
| 海外 VPS 聚合站 | `http://38.75.136.137:82/live2/xxx.m3u8?...&usign=...`（约占 1/4） | 带 usign 防盗链签名，**会过期**；服务器为私人租用（GTHost） |
| 运营商 IPTV 组播转流 | `dbiptv.sn.chinamobile.com`、`111.56.90.5`、`183.220.20.17:4321` 等 | **仅对应运营商宽带内可访问**（组播出口），跨网基本不通 |
| 阿里云公共聚合 | `http://120.76.248.139/live/bfgd/xxx.m3u8` | 央卫全套，圈内公用多年，2026-11-19 实测仍活 |
| 咪咕破解接口 | `anfeind1.kmdns.net:19999/migunohosts.php?...` | 咪咕 4K/体育 |
| 其它源主服务器 | `shyd.695847.xyz`、`rihou.cc:88` 等 | 圈内其他私人源 |

**重要**：这类源存活期短（签名过期/组播更换/源主跑路），启用前先批量探活；
长期维护应订阅上面的 GitHub URL 动态拉取而非只用本快照。

### ⚠️ 2026-11-20 实测复核：本快照当前基本不可用

用本地网页播放器（`E:\MyTool\m3u8-player`，`python server.py 8790 <本文件路径>`）按 host
抽样 24 个服务器逐条验证，结论：

| 结果 | 数量 | 说明 |
|---|---|---|
| 分片可拉 | 2 | `120.238.5.44:1698`（广东频道组）、`221.14.142.118:8000` |
| 源站 502 / 断连 | 22 | 央卫组主力 `120.76.248.139` 直连与代理出口**均 502**（源站自身停服） |

两个「能拉分片」的也无法直播播放：`120.238.5.44:1698` 的 CCTV1 单个分片 5.4MB、
下载耗时 113 秒（≈0.4Mbps），而直播分片时长仅 5 秒——永远追不上直播进度，播放器只能
停在加载中。

**判定一条源能否真播需要两个数**：① 分片能否拉到；② `分片大小 ÷ 下载耗时 ≥ 2Mbps`。
只看 ① 会把大量"能拉清单但放不动"的源误判为可用（2026-11-19 曾据此误报「央卫 52/61
可播」，次日同批全部 502 —— 源站状态本身在漂移，任何静态结论都会过期）。

**结论**：本快照不能作为 UTAO 失败后的替代供给。保留价值仅在于：格式样本、频道名清单、
以及万一上游复活时的重探基准。

## 三、为什么默认不启用（合规风险）

快照里的地址**全部是未授权流**（运营商组播泄露 + 破解接口 + 私人聚合转发）。本项目是
GitHub 公开仓库 + 带 Release 发版链路，把这些源**内置进 APK 并默认启用**等于分发盗版
直播入口，有下架/法律风险——这也是它与现用 UTAO 源的本质区别（tv2.json 的频道 URL 是
各台官网页面，播放走官方端点解密）。所以：只存档、不打包、不启用，风险自担、启用需拍板。

## 四、切换步骤（UTAO 失败时启用）

前置判断：UTAO「失败」= `api.vonchange.com/utao/config/update.json` 长期不可达或不再
更新 **且** 内置 assets/tv-web 种子频道表也失效（官网页改版播不了）。只是云端不更新、
官网页还能播的，不需要切（assets 种子够用）。

### 步骤 1：把快照变成 App 可读的频道表供给

把 [backup-live-source-taotao.txt](backup-live-source-taotao.txt) 挪到
`app/src/main/assets/live/backup_taotao.txt`（约 +647KB APK 体积，注意体积权衡；或改为
运行时从上面的 GitHub 订阅 URL 拉取 + filesDir 缓存，参照 `MarmotCloudUpdate.kt` 的
下载缓存模式）。

### 步骤 2：解析 txt → Live/Vod 频道表

在 `com.mytvb.feature.marmot` 下新增解析器，逻辑约 30 行（涛涛TV 反编译原版参考：
本机 `.trae/taotao-tv/jadx-main/sources/androidx/base/m3/k.java`，`c()` 方法）：

- 逐行读：含 `#genre#` 的行 → 新分组 `MarmotModels.Live(tag = "taotao", name = 组名)`；
  其余 `频道名,url` 行 → `Vod(name, url)` 追加进当前分组；
- 跳过空行；同名频道多行保留（天然多线路）；
- 建议支持分组白名单参数（快照 70 个分组全载入太多，优先「央卫电视」「咪咕电视」等）。

### 步骤 3：接入频道表加载

`MarmotLiveData.load()`（`app/src/main/java/com/mytvb/feature/marmot/MarmotLiveData.kt`）
现有兜底链：filesDir tv2.json → assets tv2.json → 失败。备用源作为第二兜底挂尾：两者都
失败时读备用 txt，解析后与 `mergeExtraChannels()`（同文件）同款方式合并进 lives。
也可以做成独立入口（如「设置 → 直播 → 备用源」手动触发），避免动默认链路。

### 步骤 4：播放侧加直链分支（关键差异！）

现有 `MarmotLiveActivity.playCurrent()` 的分流只认两种形态：
`tv.cctv.com/live/xxx` → CCTV 原生直拉（H5e）；`yangshipin.cn?pid=` → CMG 原生直拉；
**其余 → WebView 整页加载**。而备用源的 URL 是**直链 m3u8/rtp**，塞给 WebView 会当网页
打开（黑屏）。必须在 `playCurrent()` 分流链中、WebView 兜底**之前**加直链分支：

```kotlin
// 备用源直链频道：ExoPlayer 直拉（HLS 原生支持，无需 H5e/CMG 解密链）
else if (vod.url.endsWith(".m3u8") || vod.url.contains("/rtp/")) {
    startDirectLive(vod.url)   // DefaultMediaSourceFactory + MediaItem，比原生解密链简单得多
}
```

（`startCmgNativeLive`/`startNativeLive` 同文件已有现成的 ExoPlayer 播放器管理代码可复用，
只是 DataSource 换默认工厂、不需要解密。）

### 步骤 5：验证清单

- [ ] MuMu 实测：进直播页 → 备用分组可见 → 点播 CCTV-1（120.76.248.139 源）出画
- [ ] 组播源在本机网络不通属预期（跨网限制），勿当 bug 修
- [ ] 探活脚本先过滤一轮死源再发版
- [ ] APK 体积复核（若走了 assets 方案）

## 五、相关代码索引

| 文件 | 角色 |
|---|---|
| `feature/marmot/MarmotCloudUpdate.kt` | UTAO 云端更新（主源），头部注释有指向本文件的指引 |
| `feature/marmot/MarmotLiveData.kt` | 频道表加载/合并（备用源的接入点，`load()`/`mergeExtraChannels()`） |
| `feature/marmot/CctvExtraChannels.kt` | 代码化频道兜底先例（合并模式可参照） |
| `ui/activity/MarmotLiveActivity.kt` | `playCurrent()` 播放分流（步骤 4 的改动点） |
| 本机 `.trae/taotao-tv/` | 涛涛TV 逆向全档（UNPACKED_*.dex + jadx-main 反编译） |
