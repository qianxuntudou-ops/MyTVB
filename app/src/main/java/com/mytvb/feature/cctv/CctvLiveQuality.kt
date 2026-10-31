package com.mytvb.feature.cctv

/**
 * CCTV 直播画质档。
 *
 * 官方新版 liveplayer.js 已忽略 createLivePlayer 的 br 参数（2026-10 MuMu 实测：br=1080 与
 * br=360 拉到的都是同一条 720p 流）。真实机制：vdn 接口只下发一条 master 清单 URL（形如
 * `.../index.m3u8?b=200-2100`），CDN 按该 `b=` 码率窗口(kbps)过滤 master 里的变体
 * （BANDWIDTH 落窗才保留；窗口为空时顶格返回最高档，不会失败）。因此切画质 = 改写 master
 * 请求的 `b=` 窗口，让清单只含目标档变体，MSE 播放器自然锁档。
 *
 * 各档窗口经 cctv1/cctv13 实测标定（CDN 变体为 3200k(1080p,_pd)/1800k(720p)/1350k(576p)/
 * 900k(480p)/600k(360p)；vdn 下发的默认窗口 200-2100 不含 3200k 变体——1080P 档须把窗口
 * 放宽到 2100-8000 才能锁到真 1080p。无 1080 变体的频道选 1080 会顶格拿 720）。
 * 全部变体均为 H.264（pd/td 分片 SPS type7 实测）——vdn 源无 HEVC/AV1 可选。
 */
enum class CctvLiveQuality(
    val label: String,
    /** master 清单请求的 `b=` 码率窗口（kbps 低-高），窗口内恰含本档唯一变体。 */
    val bRange: String,
    /** [com.mytvb.feature.marmot.domain.HzItem.level] 显示排序用数值。 */
    val level: Int
) {
    P1080("1080P", "2100-8000", 1080),
    P720("720P", "1400-2100", 720),
    P540("540P", "1200-1400", 540),
    P360("360P", "200-600", 360);

    fun nextLower(): CctvLiveQuality? = entries.getOrNull(entries.indexOf(this) + 1)
}

/**
 * m3u8 master 请求改写脚本：包装 `XMLHttpRequest.open` / `fetch`，把 `...m3u8?b=低-高`
 * 的码率窗口替换为目标档（变体清单 `BR=` 与 `.ts` 分片不改写），master 只含目标变体 →
 * MSE 锁档。必须在 createLivePlayer 之前执行（官方脚本动态加载的 hlsp2p 走 prototype/
 * 全局引用，后装库同样被 hook 覆盖）。
 */
object CctvLiveQualityHook {

    fun script(bRange: String): String = """
        (function() {
          var RANGE = '$bRange';
          function rewrite(url) {
            if (typeof url !== 'string' || url.indexOf('.m3u8') < 0) return url;
            if (!/[?&]b=\d+-\d+/.test(url)) return url;
            var next = url.replace(/([?&]b=)(\d+-\d+)/, function(m, p1) { return p1 + RANGE; });
            if (next !== url) {
              console.log('mybili cctv qualityHook range=' + RANGE + ' url=' + url.slice(0, 120));
            }
            return next;
          }
          var origOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function() {
            if (typeof arguments[1] === 'string') arguments[1] = rewrite(arguments[1]);
            return origOpen.apply(this, arguments);
          };
          if (window.fetch) {
            var origFetch = window.fetch;
            window.fetch = function(input, init) {
              try {
                if (typeof input === 'string') {
                  input = rewrite(input);
                } else if (input && typeof input.url === 'string') {
                  var u = rewrite(input.url);
                  if (u !== input.url) input = new Request(u, input);
                }
              } catch (e) {}
              return origFetch.call(this, input, init);
            };
          }
        })();
    """.trimIndent()
}
