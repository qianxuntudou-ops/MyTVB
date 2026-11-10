# 红果短剧集成开发文档（HG 分支交接）

> 2026-11-09 P2 全功能完成（搜索/四板榜单/筛选面板/登录三通道/详情修复），MuMu 回归通过。
> 分支：`HG`（基于 main f37ca5de，P1 提交 54b75b30）。本文档供在其他电脑上继续开发使用。

## 一、功能现状（P2 后）

| 功能 | 状态 |
|---|---|
| 设置开关（电视直播 tab → 红果短剧组，默认关） | ✅ |
| 侧栏红果入口（CCTV影视之后，图标=果子） | ✅ |
| 六 tab 主页 + 搜索覆盖层（输入+联想+结果网格+BACK 收层） | ✅ MuMu 实测 |
| 推荐流（landpage 匿名 feed） | ✅ MuMu 实测（间歇风控见§五） |
| 详情弹窗（Googlebot UA 走 SSR `_ROUTER_DATA`，浏览器 UA 会拿到 stream 版无数据） | ✅ MuMu 实测 |
| 四板官方榜单（bookmall/cell/change GET：热播/真人/漫剧/AI剧） | ✅ MuMu 实测（官方接口直通） |
| 榜单网页兜底（fqnovel 被拒时 hongguoduanju.com/rank SSR） | ✅ 代码就位 |
| 分类筛选面板（selector_rows 8 维 chip + ChoiceDialog 单选） | ✅ MuMu 实测（8 chip 全出） |
| 登录三通道（扫码二维码+轮询 / 短信 / 导入 sessionid） | ✅ MuMu 实测（二维码拉取+轮询通） |
| 历史/收藏（本地 SP `hongguo_local`，与 B 站零共享） | ✅ |
| 播放器（media3，UP/DOWN 切集、OK 暂停、左右 ±10s、进度回写；纯 Kotlin 签名+TLS 传输+CENC 解密） | ✅ **MuMu 双包实测出画面**（签名/传输零字节码，见§五晚节） |


## 二、代码结构（P2 后）

```
app/src/main/java/com/mytvb/feature/hongguo/
├── HongguoFragment.kt          # 主容器：六tab ViewPager + 搜索覆盖层 + BACK 分层
├── HongguoFeedPage.kt          # 推荐/历史/收藏/分类共用列表页 + 筛选 chip 行（selector_rows）
├── HongguoRankPage.kt          # 四板官方榜单（热播/真人/漫剧/AI剧 RadioGroup + 大卡排名）
├── HongguoCategoryPage.kt      # 分类页（genre 子tab + FeedPage）
├── HongguoMinePage.kt          # 我的页（登录态直达 + 退出）
├── HongguoSearchPage.kt        # 搜索覆盖层（输入框呼 IME + 联想词 + 结果网格）
├── HongguoLoginDialog.kt       # 登录弹窗（扫码/短信/导入 三通道 tab）
├── HongguoDetailDialog.kt      # 详情弹窗（AppCompatDialog + DialogWindowFit.overlay）
├── HongguoPlayerActivity.kt    # 独立播放器（resolvePlayUrl=WebView→OkHttp 双通道）
├── HongguoRepository.kt        # 数据门面：身份/搜索/榜单(带网页兜底)/播放/账号/收藏
└── net/
    ├── HongguoCrypto.kt        # 签名栈（SM3/Gorgon/Helios/F13×2branch/Medusa/Proto/XMXOR/AESV3）
    ├── HongguoArgus.kt         # 真版 X-Argus/X-Ladon/X-Helios（F0.o/F0.x1 逐字段对拍）
    ├── HongguoEnv.kt           # 设备环境（如实上报）+ ua(versionCode) 模板
    ├── HongguoHttp.kt          # 共享 OkHttpClient（HongguoTls）
    ├── HongguoTls.kt           # TLS 形态（ALPN ttp/1.1+15 套件+TLS1.3/1.2+HTTP/1.1）
    ├── HongguoMp4Decrypt.kt    # CENC 解密（senc→AES-128-CTR→moov 重建）
    ├── HongguoDeviceRegister.kt# TTEncrypt V5 设备注册（registerFresh 内核可独立调用）
    ├── HongguoSession.kt       # 设备身份+账号票据持久化（SP hongguo_session）
    ├── HongguoClient.kt        # landpage feed + rank GET（均 Kotlin HGTV 形态签名）
    ├── HongguoSearch.kt        # 搜索（72232 GET，Kotlin HGTV 形态签名）+ 联想词（网页免签）
    ├── HongguoAccount.kt       # 登录三通道（扫码网页免签/短信 passport/导入：cookieStore.xml 全文/32hex sessionid/cookie 串）+x-tt-token/sid_guard 过期解析
    ├── HongguoSync.kt          # 账号云同步+保活（进度/收藏/历史/预约接口 + beat 心跳 + boot 校验 + token 滚动）
    ├── HongguoWeb.kt           # 网页详情（Googlebot UA→_ROUTER_DATA）+ 榜单网页兜底
    └── HongguoPlay.kt          # 播放地址解析（host 轮询+K-M1 兜底+kotlinSignedPost）
```

**2026-11-10 夜瘦身处决（-740 行，全功能回归绿）**：X-Medusa 全家（HongguoCrypto 764→493 行，去黑盒定案 HGTV 无此头）/HongguoWebSigner 整文件+播放 WebView 通道（实证从未成功）/播放兜底②（video_model/v1+multi/v1 重发，K-M1 之后从未命中）+singleBody+postVideoModel 非 hgtvLane 分支（VENDOR_* 常量）/randomId/ensureRegistered/Identity.registeredAt/HongguoPlayDebug/medusa 相关测试×2。bootVerify 补接线（MinePage 登录态刷新异步校验）。
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

## 五、播放通道攻坚结论（2026-11-10 终极破解版，接手必读）

### 2026-11-10 下午终极破解：HGTV 全链路实证 + L1 签名通道打通（MuMu 实测出片源）

**方法论教训**：上午「MuMu 全拒/缓存假象」两个结论全部错误——起因是 frida hook 脚本用了 jadx 显示字段名（f2240a）取 URL 恒 undefined → 过滤条件永不命中 → 「没抓到请求」的假证据。**hook 混淆类必须先 dexdump 取真实字段名**（M2.a/b/c/d、S0.s1.d）。

**frida 实抓 HGTV 全链路**（attach 模式+真实字段名，hook_hgtv_ssl.js）：
- 播放：`POST api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1`（无尾斜杠）query 73932 档案+klink_egdi 最前+cdid+**如实上报设备环境**（2211133C/V417IR/1280*720/240，不伪装），gzip body=biz_param+dr_scene+mixed_video_id_map，RESP 386KB/15 集 ✅
- 视频流：`GET vas-lf-x.snssdk.com/video/fplay/1/<md5>/<vid>?...&stream_type=encrypt`（**免签**，m2=null，URL 带 auth_policy/ptoken）
- 头集（弹幕同构）：UA=`com.phoenix.read/71332 (Linux; U; Android 15; zh_CN; 2211133C; Build/V417IR; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)`（**UA 版本 71332 与 query 73932 不同档**）+真版 ladon/argus+gorgon/helios/khronos/ticket/stub+x-tt-request-tag+passport-sdk-version 6031190（播放 G0 改写后无 request-tag/x-tt-dt、passport=5051452、加 lc/x-ss-dp/x-vc-bdturing/x-tt-trace-id/Content-Encoding:x-reading-request）+**无 x-medusa**
- HGTV 号是它自己 pm clear 后**新注册的**（非内置老号）；**Argus field23 三元组从环境 map 动态取**（"25053RT47C" 只是 fallback 默认值，proto 与 query 环境一致是关键）

**服务端行为模型**（Node 交叉实验矩阵实证，bisect_*.js）：
1. **每号首请求**：新注册号只有第一次播放请求有效（首发 ✅ 次发 ❌ 与客户端栈无关）；Node 线上版稳定通过=EMPTY 自动 reset 重注册
2. **信任号分级**：HGTV 的号可跨连接多次请求（行为分高）；我们新号跨请求/跨连接即拒
3. **入口分集群**：`sinfonlineb+/v1/`（M1 可用）；`sinfonlinea+/player/v1` 要求 HGTV 完整签名形态（M1 弱版单换 host 或单换路径都过、双换拒；Kotlin 移植签名在 Node 也拒=移植细节有差）
4. **DNS 分流**：sinfonlinea→CNAME 字节直连机房（116.169.164.x，l7-online-self-max-v6.s.dsa.cdnbuild.net）；sinfonlineb→金山云 CDN（q1-quic.gslb.ksyuncdn.com 58.144.x）。pi5 域名公网不解析（pcap 里的 pi5 连接来自 MuMu 其它 TTNet app，与 HGTV 无关）
5. **注册 Cookie**：注册下发 install_id/ttreq 等 Set-Cookie，信任号不需要回带（删 Cookie 重放仍过）

**打通方案（历史方案，当晚已拆除——见下方去黑盒节）**——`HongguoL1.signAndPost`（1.3.6 字节码，双版本自适应）：
- **签名也走 HGTV 字节码**：反射调签名器产出 url+签名头集（UA 由 query map 派生）→ Kotlin 侧做 G0 改写（删 request-tag/x-tt-dt、passport=5051452、加 lc/x-ss-dp/x-vc-bdturing/x-tt-trace-id/Content-Encoding(gzip 时)/store-region）+E0 外层头 → L1 okhttp 传输
- **双版本混淆映射**（运行时按签名器类名自动探测，真实类名均 dexdump 验证）：
  - 1.3.4：`S0.s1.d`/M2(a,b,c,d)/okhttp=L1 包(x,y,D,C,w)/TLS `S0.S0.a`/Protocol `L1.z.c`/RequestBody `H0.j.b`
  - 1.3.6（2026-11-10 起 embed 默认）：`F0.x1.e`/`F0.c3`(a,b,c,d)/okhttp=H1 包(z=Builder,A=Client,F=Request.Builder,E=Call)/TLS `F0.U0.a(H1.z)`/Protocol `H1.B.c`/MediaType `H1.y.a`/RequestBody `H1.H.c`/Response `H1.J`(c=code,g=body)
  - 1.3.6 坑：`H1.F.b(String,H)` 非 public（getMethods 取不到，须 declaredMethods+setAccessible）；headers 走 Builder 内部字段 c（`B.Z.u`）；client=`new H1.A(z)`；1.3.6 = pan8664716 独立线（修复推荐空屏/播放重试）
- **播放端点策略**（12:52 双剧回归通过）：主=`player/v1`(73932 档案+gzip body)；**部分内容报 code=100001 参数错误**→自动 L1-M1 兜底（`sinfonlineb multi_video_model/v1/`+71332 档案+明文 body+L1 签名——编码直接下发 hevc/h264，bvc2 问题也随之消解；**video_platform=3 是伪解**，对 player/v1 恒报参数错误，已删）
- **feed（推荐/分类）也走 L1 签名**：landpage 73532 档案+真环境+注册号复用（空响应才换号），gzip=false
- **每号新号首发**：`fetchModelEntry` 每轮 `registerFresh()`→立即首发（Identity.registeredAt<30s 可复用）；号废即换
- **注册+播放共享连接池**：`HongguoHttp` 单例（HongguoTls 同款）
- **环境如实上报**：`HongguoEnv`（Build.MODEL/RESOLUTION/densityDpi 等动态读取，host_abi 恒 arm64-v8a）；注册 payload/query/播放 query 三处同源
- **video_list 双形态**：player/v1 响应的 video_model.video_list 是 **JSONArray**（multi/v1 是 JSONObject 键值）——parseEntry 已兼容
- 打包：`app-debug-l1.apk`（`scripts/embed_hgtv_dex.py --hg=136 默认`塞 dex+重签名；**assembleDebug 必须成功后再跑 embed**，否则装旧包）

**CENC 加密流解密播放（已完成，2026-11-10 12:00 MuMu 实测出画面+切集全通）**：
- `HongguoMp4Decrypt`（Node hongguo.js decryptMp4Buffer 的 Kotlin 移植）：parseBoxes→trackInfo（stsz/stco/stsc/senc→样本偏移+8B IV 表）→逐样本 AES-128-CTR（IV=nonce+8B 零计数器）→rebuildMoov（prone senc/saio/saiz/sgpd/sbgp、encv/enca 经 sinf/frma 还原原始 codec 条目、stco/co64 重定位、moov 前移 mdat 前）
- **坑**：stco 重定位就地修改后必须**重新 serialize(tree)**（Node 语义），返回重定位前的序列化会导致容器解析失败（0xbb9）
- 播放器：`HongguoPlayerActivity.resolvePlayable`——下载整集（okhttp 直链无风控）→解密→`cacheDir/hongguo/<vid>.mp4` 缓存→media3 播本地（同集二次进入秒开）
- 实测：1080p hevc 单集约 8.7MB，下载 1.2s+解密 0.3s，state=READY 稳定；DOWN 切集每集 ~3s 全链路

### 2026-11-10 晚：彻底去黑盒——签名+传输 100% Kotlin 源码（L1 已整体拆除）

**黑盒已除**：`app-debug.apk` feed/rank/search/播放/切集/CENC 全功能可用（MuMu 实测）。**L1 字节码通道（HongguoL1.kt/embed_hgtv_dex.py/app-debug-l1.apk）已删除**——embed 的 HGTV dex +1.9MB 包体，纯 Kotlin 通道与之实测等价后无保留价值。

**签名栈对拍定案**（jadx `--single-class F0.x1` 破 Windows 大小写覆盖坑后逐行核对）：
- F0.x1/F0.o **纯 Java 实现，零 native**：分支判定=ivTransform(SM3(query), 0x20230928)+MD5(body)+le32(ts)（与 HongguoCrypto.branch 本就一致——Y0.V 是 SM3 不是 MD5）；gorgon/helios/ladon 算法与 Kotlin 版逐位一致；**HGTV 栈根本没有 X-Medusa**（我们 Kotlin 链也不发它）
- **HongguoArgus 修 3 处对拍差异**（此前被拒的直接原因）：①field13=`SM3(MD5(body))` 前 6 字节（少包一层 SM3）②field3=`le32(rand[6..10)) & 0x7fffffff`（漏位清零）③field23 嵌套 field4=`21474836480L`(0x500000000)（写成 2147483648 差 10 倍）
- **E0.b(2)=hex 编码**（不是 base64）——gorgon/stub/helios-key 输出形态本就一致
- r3.c UA：POST 带 Cronet 尾串/GET 短版，版本取 query 的 version_code（版本位不敏感）；CP_NONE="none"
- argus key 表=`SM3(secret+"3ccc567b"+secret)` 72 项（C0173m case 0 预计算），常量 0x3DC94C3A046D678B ✓

**传输层定案**：HGTV 的 TLS 定制（F0.U0+SSLSocketFactory 包装器）只是 cipher 套件交集+TLSv1.3/1.2，无 BoringSSL——**JVM okhttp+HongguoTls 完全等价**（实测 TLS_1_3/AES_256_GCM 与 L1 字节码传输同响应）。

**通道架构（HongguoPlay，全纯 Kotlin）**：`fetchModelEntry` 每轮=新号+新 host（round0/1/2 = sinfonlinea/b/c，HGTV F0.n3.f() 同款集群轮询）→ 兜底 K-M1（`sinfonlineb multi_video_model/v1/`+71332 档案+明文 body，`kotlinSignedPost`=F0.x1.d 形态签名+JVM 传输）。**player/v1 端点 2026-11-10 起服务端整体收紧**（三 host 全 code=100001，与签名无关——HGTV 本尊靠多 host 轮询+fallback_api 二跳同路径续命）；multi/v1+71332 档案是实际可用端点。
- feed/rank/search 全部 Kotlin HGTV 形态（HongguoClient.postOnce/getOnce、HongguoSearch：插入序 query+真版 Ladon/Argus/Helios+G0 改写头集+TLS 定制传输；**P1 占位签名 base64(be32(ts)) 是空响应主因**）
- HongguoEnv.ua(versionCode)：UA 模板统一（feed 73532/播放 71332/搜索 72232 GET 短版）

**验证基线（改签名/通道后必跑）**：feed len>70000 / rank len>200000 / search len>40000 / 播放 state=3 / 切集 CENC decrypt 日志（MuMu 实测参考值：feed 78KB/rank 245KB/search 47KB/K-M1 25KB）。

**遗留（P3）**：MFA 短信上行、竖屏 9:16 模糊垫底形态、弹幕/评论（明确不做）；player/v1 恢复时通道自动回到主端点（round0 优先）。

**踩坑记录（勿再踩）**：
- frida hook 混淆类字段名必须 dexdump 验证（jadx fNNNN 是显示名）；frida Map.toString 在 hook 里变 [object Object]，用 Java.cast(...).toString()
- frida-server 必须原生 x86_64（ARM 版跑在 houdini 下 spawn 崩+连接不稳）；spawn 模式被 HGTV 反检测杀，**attach 模式可用**
- MuMu adb 命令要 MSYS_NO_PATHCONV=1；HGTV/MyTVB 的 TV 页面导航用「卡片焦点 LEFT→侧栏→DOWN 果子(第3图标)→OK」路径稳定
- Node 签名链借用：createRequire(app-source.js 目录)+源码切片 new Function 提取 playbackRequest（bisect_identity.js 模板）



### 2026-11-10 HGTV 全面破解（静态+动态双实证）

**HGTV 播放链路全解**（`.trae/HGTV/jadx/`，com.hgtv.star.core.b 7027 行 + S0 包）：
- 传输层：`L1.x`（okhttp 魔改名）`protocols=[HTTP_1_1]` + `dns(P0)`（HTTPDNS+运营下发 IP 表，实际与系统 DNS 同 IP）+ `S0.a()` TLS 定制（自标注 **tls-7**，作者跟风控迭代第 7 版）
- **关键伪装（MuMu tcpdump ClientHello 实测，不是静态代码说的）**：`ALPN=ttp/1.1`（TTNet 私有值！静态代码里 ConnectionSpec 的 4-CBC 是早期遗物，TLSSocketFactory 层跳过 apply）；cipher=Conscrypt 默认 15 套件序；TLS1.3/1.2；与系统 DNS 同 IP 直连
- 签名：`C0257s1.d`（khronos 分支循环/RC4 变种 gorgon/Simon34 helios[salt 1588093228]/真版 ladon/argus）+ G0 头集改写（删 x-tt-dt/x-tt-request-tag、passport→5051452、加 lc/x-tt-trace-id/Content-Encoding）
- 身份：内置公共老号 `W2.f3571a`（device_id=1905892595378490/iid=1905892595382586，Redmi 25053RT47C/71332 档案）+ 未注册播放；**播放数据整季本地缓存**（G0 每批 15 集，切集零网络）——pm clear 后甚至离线可播（内置离线内容包）
- **frida hook 实锤**：hook E0/C0257s1.d/L1.C.d+b 全部抓不到播放请求=走缓存；HGTV 能播≠它实时过风控

**我们的复刻与逐层证伪（每项都实测）**：
1. ✅ `HongguoTls`（ttp/1.1+15cipher+HTTP/1.1）——MuMu 抓包验证 ClientHello 与 HGTV 完全一致（CBC 四件必须 OpenSSL 名 `*_CBC_SHA`，JSSE 名被 Conscrypt 交集过滤）
2. ✅ 修复真 bug：`x-ss-req-ticket` 空串（signRequest branch 调参取错 map）、`x-medusa` 丢失、escapeGo 对齐 pythonUrlEncode（`*` 不编码/空格 %20）
3. ❌ HGTV 全头集+UA73932+老号：Node 回放也空（违反 M1 实证：UA73932/多余头触拒）——已回滚 M1 构造
4. ❌ 真版 Ladon/Argus：设备 proto 与 query 档案不一致反而拒——M1 弱版（ts 字节）通过，已回滚
5. ❌ klink_egdi 首参：**毒药**（Node A/B：M1+egdi 必空；HGTV 带 egdi 能过靠完整行为链）——已移除
6. ❌ 连接预热+复用（模拟 PSK 会话行为）：仍空
7. ❌ 「号被标记」证伪：Node 号在 Node 首用 PASS 后塞 MuMu 用 → 空，且该号回 Node 也死——**MuMu 出口对请求本身识别，与号无关**

**终局判定**：MuMu 出口风控维度深于 TLS ClientHello（ttp/1.1/15cipher 全对齐仍空；Chromium WebView 栈也空），剩余嫌疑=HTTP/1.1 头编码细节/TLS 会话 ticket 内容/连接行为时序/TCP 层——**静态可复刻层已穷尽**。真机（kotlin-tv 同栈被 release 用户验证可播）仍是最终判据。

### 2026-11-09 初版结论（历史保留）


**最终突破（22:40）**：Node 矩阵探针 `.trae/hg-verify/node_hgtv_matrix.js` 找到稳定通过组合 **M1**：
`player/v1 + gzip body + batch=单vid + query 73932(official-v1 档案+cdid+klink_egdi+ts) + UA 71332 Cronet + 简头集（UA/Accept/req-ticket/sdk-version/xs-from-web/khronos/ladon/argus/gorgon/helios/medusa/Content-Type/x-ss-stub/Content-Encoding:gzip）`——**多余头（request-tag/passport-sdk/x-tt-dt/bdturing/reading-request）与 UA73932 反而触发拒绝；batch>1 也拒**。M1 在 Node(OpenSSL) 下稳定返回 26KB 播放数据。Kotlin 侧已完整实现（`HongguoPlay` hgtvLane + `HongguoArgus` 真版 Ladon/Argus + 注册层 klink_egdi）。

**传输层终局矩阵**（M1 同构造）：Node=**200 数据**；OkHttp Conscrypt（h2/1.1/cipher复刻Node序）=空；Chromium WebView（同源桥接页+gzip base64）=空；X5=MuMu 无 x86 内核不可用。**MuMu 模拟器网络出口对非白名单 TLS 指纹全拒**（HGTV 本尊 okhttp3 能过=其指纹恰在白名单；OkHttp4/5 无法复刻 okhttp3 的 h2 帧序）。**真机是最终判据**：kotlin-tv（同 OkHttp4/5+Conscrypt）在真实设备被 release 用户验证可播=真机环境风控宽容，M1 组合大概率直通。

播放接口 `multi_video_model`（71332 明文构造，签名经中继验证有效）的**传输层评分风控**：

| 传输层 | 出口 | 结果 |
|---|---|---|
| Node axios（Node 原生 TLS） | 宿主机直连 | **通** |
| OkHttp Conscrypt（h2 或 1.1） | MuMu 直连 | 空（HTTP 200 len=0） |
| WebView XHR（Chromium 栈，同源桥接页） | MuMu | 空 |
| OkHttp Conscrypt 经宿主 Clash 出口 | 宿主网络 | 空 |
| HGTV APK（反编译版，Conscrypt+自家 okhttp） | MuMu | **通**（走 73932+gzip+player/v1 构造，但我照抄后在 Node/Kotlin 均空——还缺因子，疑似真版 X-Ladon/X-Argus，生成器在 `HGTV jadx S0/AbstractC0240o.java` 238 行未移植） |

**结论**：不是 TLS 指纹单因子，是「传输层×请求构造×IP」综合评分；MuMu 出口对 71332 POST 接口几乎全拒（feed 也间歇空），但 **GET 接口（rank/search）放行**、`hongguoduanju.com` 网页域完全放行。**真机（真实设备网络+Conscrypt）是下一判据——官方 App 同栈可用，大概率直通**。当前实现（2026-11-10 起改）：`resolvePlayUrl` 先 OkHttp（`HongguoTls` 伪装栈）再 fallback WebView 通道（`HongguoWebSigner`）。

**若真机也不通的三条路**（按优先序，2026-11-10 更新：①已实施被 MuMu 证伪但真机待验）：
1. ~~移植 `AbstractC0240o`~~ 已移植（`HongguoArgus`）——MuMu 实证真版 ladon/argus 设备 proto 与 query 档案不一致反而拒，M1 弱版可用
2. okhttp3 依赖隔离（shade/relocate 同包名冲突，HGTV 的 L1 即魔改 okhttp3——若其魔改含传输层补丁则这是唯一复刻路径）
3. Cronet 依赖（Chromium 网络栈 +8MB）/ 网页播放通道（hongguoduanju.com 播放器接口逆向）

## 六、P2 实现要点速查（接手改代码前看）

- **详情页必须 Googlebot UA**：浏览器 UA 返回 stream 渲染版（data-fn-args 仅推荐列表，无 _ROUTER_DATA/seriesDetail）
- **筛选面板**：首页 `need_selector_panel=true + req_type="default"`，翻页 `false/"only_content"`；`select_items` 8 键全发（空选=空数组）；creation_status 行部分 scene 不下发→硬编码补齐；`selector_item_id` 题材格式 `cate_\d{1,8}`
- **四板榜单**：GET `cell/change/v`，`tab_type` 首页 26 翻页 18，`cell_id=7470092475068071998` 固定，四板=(selected_items, sub_selected_items) 二元组；GET 无 body→无 X-SS-STUB/Content-Type
- **搜索**：72232 profile + UA 无 Cronet 后缀 + `tab_type=11` + 相关性过滤（分词全命中元数据，防无结果塞热门）；翻页游标 search_id+passback+next_offset；联想词走 `hongguoduanju.com/incent_resource/suggestion`（免签）
- **登录**：扫码全走 hongguoduanju.com/passport/web/*（免签，浏览器头+cookie jar）；短信走 api5-normal-lq passport（签名 form POST，手机号每字节 XOR 0x05 hex）；导入通道（2026-11-10 对齐 HGTV 1.3.6 L0.M0）收三种输入——官方 App cookieStore.xml 全文（`HongguoCookieStoreParser`：snssdk.com 域正则+TLV hex 值解码+16 项白名单拼串）/32 hex sessionid/含 sessionid= 的 cookie 串，统一走 /reading/user/info/v1 校验（失败不落盘）；fetchProfile 发白名单序完整 Cookie 串（非单 sessionid）；票据存 HongguoSession.accountCookies（登出只清账号票据保留设备身份）；**免 root 导出路**：`tool/hongguo_cookie_export.user.js` 油猴脚本（Tampermonkey，需 GM_cookie）——电脑浏览器登录 hongguoduanju.com 后一键导出仿 cookieStore.xml（TLV hex 编码与 App 解码器互逆，已 Node 往返+端到端验证）+复制 sessionid，文件内容直接粘进 App 导入框
- **WebView 通道**（HongguoWebSigner）：shouldInterceptRequest 拦 `sinfonlineb.fqnovel.com/__hg_bridge__` 注入 HTML（与 API 同源免 CORS），JS XHR setRequestHeader 保留大写头名，UA 在 WebView settings 设（XHR forbidden）；响应 encodeURIComponent 后 console 桥回
- **对话框协程坑**：Dialog 里 lifecycleScope.launch 默认 Main dispatcher，**网络调用必须 withContext(Dispatchers.IO)**（NetworkOnMainThreadException）

## 七、P3 待办（优先级序）

1. 竖屏剧播放形态（9:16 居中+两侧模糊垫底）——当前 RESIZE_MODE_FIT 已居中
2. 弹幕/评论（明确不做）

~~MFA 短信上行~~ **2026-11-10 夜已完成（对照 HGTV F0.O1.h/j 逐字段对齐）**：
- 语义：服务端在 sms_login 响应下发 `channel_mobile`（目标号码）+`sms_content`（短信内容）等（verify_center_secondary_decision_conf 对象/字符串双形态+verify_ways 数组兜底），**App 只显示指引**，用户拿自己手机手动发送，点「我已发送」后同接口重发（conf 键值展开回传，sms_code_key 值过 XOR 0x05，+passport_mfa_retry_tag=1）；服务端经运营商确认上行短信后放行——App 全程不发短信。
- 实现：`HongguoAccount.smsLogin` 遇 MFA 抛 `MfaRequiredException(challenge)`；`smsLoginMfa(phone, code, challenge)` 确认；`HongguoLoginDialog` 短信 tab 加 text_mfa_tip+button_mfa_sent（新挑战自动刷新指引）。
- 验证：编译+16 单测全绿+装包零崩溃；**真实 MFA 触发链路无法实测**（需服务端对特定账号下发挑战），协议按 HGTV 反编译逐字段对齐。

~~账号云同步全量~~ **2026-11-10 夜已完成（对照 HGTV 账号模块逐接口对齐，HongguoSync.kt）**：
- 业务接口（73932 档案+明文 JSON body+Cookie/x-tt-token 登录头，b.java h()/b1() 同构）：
  read_progress/upload（播放进度→跨设备续播，播放器 saveProgress 挂点+20s 节流）、
  bookshelf/video/update（收藏云同步，toggleFavorite 挂点，operate 0=加/1=移除）、
  read_history/update（历史上传，is_delete 双形态）、user/subscribe/list+delete（预约，数据层就位 UI 后接）；
- 保活（H0/g.java 同构）：token/beat/v2 心跳（30min 阈值+播放启动触发 scene=play）、
  account/info/v2 boot 校验、sid_guard 临期 3 天预警（Session.accountExpires）、
  x-tt-token 滚动续期（响应头 X-Tt-Token/Set-Cookie >=99 字符，C0051b 同款）；
- 语义：本地为准云端尽力（cloudAsync 失败静默，SESSION_EXPIRED 只停云同步不清本地）；登录通道 saveAccount 统一收 token/过期。
- **并发烧号修复**：feed(POST)/rank(GET) 共用 apiIdentity 单槽+注册互斥（此前三页并发首启一次烧 3 号→现在 1 号/启动，MuMu 实测）。
- 验证口径：未登录静默短路+播放回归 state=3 无回归已实测；**在线链路（进度/收藏真上云、beat 真保活）待真实账号导入后实测**（MuMu 无红果账号凭据）。

~~号消耗优化~~ **2026-11-10 夜已完成（device_token 根因+号模型）**：①**注册响应的 `device_token` 是号活性关键**——HGTV 每个 API 请求 query 随带（E.java:4397），我们与全部开源参考线均丢弃此字段；已补齐全链（Identity/Session K_DEVICE_TOKEN/播放 query）；②全局改 HGTV 号模型——播放（HongguoPlay）一个号贯穿 host 轮询+空响应才经 rotateIdentity 自愈（Repository.registerAndSave 持久化到 session）；K-M1 两发保底；search 轮换走 registerAndSave；rank GET 号内存复用；③**MuMu 实测定案**：同号 3 集连播 12 连发全成功零重注册（切集 0.7s/CENC 0.4s/state=3）——旧「每号首请求有效次发即拒」结论是签名 bug 时代（argus 硬伤修复前）的观察，已不成立；④HGTV 重注册会把旧 device_id/iid/klink_egdi 塞进注册 payload（b.java:5074 账号继承语义），我们 rotateIdentity 全新随机——可选后续对齐。

## 附、相关记忆条目（本机 ZCode 记忆库）

`hongguo-p1-impl`（本次实现全档）/ `hgtv-decompile-reference`（HGTV 溯源+接口全景）/ `hongguo-refs-cloned-hg-branch`（参考项目入库）。其他电脑首次接手先读本文档，再按需查记忆。
