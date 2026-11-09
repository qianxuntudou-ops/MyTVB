# 红果短剧集成开发文档（HG 分支交接）

> 2026-11-09 P1 全功能完成并打包通过。本文档供在其他电脑上继续开发使用。
> 分支：`HG`（基于 main f37ca5de）。改动：8 个修改文件 + `feature/hongguo/` 新模块（见文末清单）。

## 一、功能现状（P1 已完成）

| 功能 | 状态 |
|---|---|
| 设置开关（电视直播 tab → 红果短剧组，默认关） | ✅ |
| 侧栏红果入口（CCTV影视之后，图标=果子） | ✅ |
| 六 tab 主页：推荐/历史/收藏/榜单/分类/我的 + 搜索胶囊（占位） | ✅ |
| 推荐流（landpage 匿名 feed，横版卡无限滚动） | ✅ 线上验证 |
| 详情弹窗（网页端免签名，封面/简介/收藏/选集网格 10列） | ✅ 线上验证 |
| 播放器（独立 media3，UP/DOWN 切集、OK 暂停、左右 ±10s、进度回写） | ⚠️ 签名已验证，TLS 待真机 |
| 历史/收藏（本地 SP `hongguo_local`，与 B 站零共享） | ✅ |
| 榜单（landpage 热度序近似；官方四板 P2） | ✅ 近似 |
| 分类（全部/漫剧/AI剧 genre 切换；七维筛选面板 P2） | ✅ 基础 |
| 我的（游客态；扫码/短信/导入登录三通道 P2） | ✅ 占位 |

**关键待验证**：播放接口 `multi_video_model` 对 TLS 指纹敏感——JVM 单测（SunJSSE）空响应、Node（BoringSSL）通；Android 真机 OkHttp 走 Conscrypt（BoringSSL 系）大概率直过。**换电脑第一件事=装 MuMu/真机跑通播放链路**。

## 二、代码结构

```
app/src/main/java/com/mytvb/feature/hongguo/
├── HongguoFragment.kt          # 主容器：六tab ViewPager + tab焦点导航
├── HongguoFeedPage.kt          # 推荐/历史/收藏共用列表页（横版卡 Grid）
├── HongguoRankPage.kt          # 榜单页（大卡双列排名号）
├── HongguoCategoryPage.kt      # 分类页（genre 子tab + FeedPage）
├── HongguoMinePage.kt          # 我的页（游客态）
├── HongguoDetailDialog.kt      # 详情弹窗（AppCompatDialog + DialogWindowFit.overlay）
├── HongguoPlayerActivity.kt    # 独立播放器（media3，manifest 已注册）
├── HongguoRepository.kt        # 数据门面：身份管理 + feed/详情/播放 + 收藏/进度
└── net/
    ├── HongguoCrypto.kt        # 签名栈（SM3/Gorgon/Helios/F13×2branch/Medusa/Proto/XMXOR/AESV3）
    ├── HongguoDeviceRegister.kt# TTEncrypt V5 设备注册（registerFresh 内核可独立调用）
    ├── HongguoSession.kt       # 身份持久化（SP hongguo_session）+ HongguoGzip 手写gzip流
    ├── HongguoClient.kt        # landpage feed（匿名随机身份）
    ├── HongguoWeb.kt           # 网页详情（hongguoduanju.com /detail _ROUTER_DATA 提取）
    └── HongguoPlay.kt          # 播放地址解析（video_model→video_list→codec过滤→spade解密）
```

修改的既有文件：`TabBarView`（button_hongguo+焦点链）、`MainActivity`（HONGGUO_TAB_INDEX=6/ME→7/SEARCH→8+工厂+applyHongguoEntryVisibility）、`SettingsFragment`（KEY_HONGGUO_ENTRY）、`MeFragment`（**event.index 6→7**，加新侧栏tab必须同步此处！）、`view_tab_bar.xml`、`strings.xml`、`AndroidManifest`、`build.gradle.kts`（testImplementation org.json）。

## 三、红果接口协议要点（接新接口必读）

### 接口 profile 表（每个接口版本档案不同！）

| 接口 | version_code | UA | F13 branch | 身份 |
|---|---|---|---|---|
| landpage feed `/reading/distribution/category/landpage/v/` | 73532 | 主UA(Redmi) | 2 可过 | 随机假 id 即可 |
| 播放 `/novel/player/multi_video_model/v1/` | **71332** | **PLAYBACK_UA(71532三星)** | **0**（tt02变体） | 建议真注册（Node 有专门注册器） |
| 搜索 `/reading/bookapi/search/tab/v` | 72232 | SEARCH_UA | 0 | 真注册+cdid 格式校验 |
| 网页详情 `hongguoduanju.com/detail` | — | 浏览器 UA | — | 免签名 |

### 请求信封（播放为例，feed 见 HongguoClient.postOnce）

- query 参数集：见 `HongguoPlay.postVideoModel`（ac/aid/app_name/.../ts 在 feed 有播放无）
- 必带头（**X- 系头名必须大写，服务器对头名大小写敏感**）：X-Khronos / X-SS-Req-Ticket / X-SS-STUB(md5 body 大写hex) / X-Gorgon / X-Ladon(BE(ts) base64) / X-Argus(LE(ts) base64) / X-Helios / X-Medusa + UA/Accept/Accept-Encoding:identity/Content-Type/Sdk-Version/X-SS-DP
- 空响应（HTTP 200 + len=0）= 身份/签名被拒：轮换身份重试 3 次
- 签名走 `HongguoCrypto.signRequest(TreeMap, body, stampMs, deviceId, targetBranch, version)`——query 必须 TreeMap（与 Go url.Values.Encode 字典序字节级一致）

### 签名栈三个已知深坑（改前必读）

1. **Gorgon KSA 单向覆盖**：`s[i] = s[j]`，s[j] 不回写（不是标准 RC4 swap）
2. **位运算优先级**：Go `^` 高于 `|`，Kotlin 位运算符同层从左到右——跨运算符表达式必须显式括号
3. **Helios 输出 LE**：u64 写出低位在前；写反后 base64 前 5 字符仍相同，看前缀查不出
4. Kotlin Byte 无位运算中缀：必须 `(b.toInt() and 0xFF)` 转 Int

### 验证基线（改签名栈后必跑）

```bash
./gradlew testDebugUnitTest --tests "com.mytvb.feature.hongguo.net.HongguoCryptoTest"
```
全绿 = SM3 国标向量(abc/空串/a×64/a×65) + Gorgon/F13/Helios 固定输入对拍（基准来自 Node 线上版）+ feed 联网冒烟。
注意：`endToEndPlaySmoke`/`minimalPlaySmoke` 在 JVM 上播放接口会因 TLS 指纹空响应（非 bug，见上）；连续快速跑多个联网测试可能触发 IP 限流，间隔再试。

## 四、开发环境与参考资产

- **参考项目**（`.trae/`，均完整可用）：
  - `hongguo-tv/`（自研 Go 版，**签名/账号算法的权威蓝本**，翻译无 GPL 问题）
  - `hongguo-open-player/`（自研 Node 版，`src/server/native/app-source.js` 是 feed/播放/搜索的线上验证实现；node_modules 已装，探针可直接跑）
  - `HGTV/`（反编译 APK，接口响应字段参考）
  - `hongguoTV-tsukiyo/`（GPL-3.0，只做行为对照勿抄代码）
  - `hg-verify/`（对拍/中继/消融探针脚本集：node_landpage_probe / node_gorgon_probe / node_medusa_probe / node_medusa_relay / tls_relay / ablation / dump_server）
- **对拍方法论**（排签名问题按序用）：
  1. 权威向量（python `pip install gmssl` 算 SM3）
  2. 函数级定输入对拍（Node 侧文本 patch 固定随机数）
  3. **中继实验**：Kotlin dump 请求落盘（`medusaRelayDump` 测试）→ node 用 fetch 重放 → 切分「签名错」vs「传输层被拒」
  4. 头消融（ablation.js）
- UI 设计稿（11屏）：会话可视化文件，焦点动线/主题色/榜单样式等按设计稿打磨

## 五、P2 待办（优先级序）

1. **真机/MuMu 验证播放 TLS**（换电脑第一件事）——若 Conscrypt 也被拒：备选方案 a) OkHttp 定制 ConnectionSpec 模拟 Chrome JA3；b) 播放签名请求走 WebView（CCTV H5e 同款方案）
2. **加密流播放**：`PlayUrl.contentKeyHex != null` 的 spade 加密流（AES 解密 DataSource，参考 Node decryptSpadeUrl/deriveContentKey 已有 Kotlin 实现可直接复用 key）
3. bytevc2 降档提示（codec 过滤已有 UNSUPPORTED_BYTEVC2 分支，接 UI 提示）
4. 搜索页（suggestion+search/tab，profile=72232，参考 Node signedSearchRequest）
5. 官方四板榜单（RankBoard 网页路由 hot-drama/hot-real/hot-comic/hot-ai）
6. 分类七维筛选面板（landpage need_selector_panel=true + selector_rows 解析）
7. 登录三通道：扫码/短信/导入参数（蓝本=自研 Go `account_scan.go` 388行 + `account_phone.go`，票据存 HongguoSession，与 B 站零共享）
8. 竖屏剧播放页形态（9:16 居中+两侧模糊垫底，设计稿③）——当前 RESIZE_MODE_FIT 自适应
9. 弹幕/评论（端点在 HGTV 反编译 core/b.java：/novel/commentapi/*）

## 六、相关记忆条目（本机 ZCode 记忆库）

`hongguo-p1-impl`（本次实现全档）/ `hgtv-decompile-reference`（HGTV 溯源+接口全景）/ `hongguo-refs-cloned-hg-branch`（参考项目入库）。其他电脑首次接手先读本文档，再按需查记忆。
