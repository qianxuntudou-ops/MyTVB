package com.mytvb.feature.cctv

import androidx.annotation.StringRes
import com.mytvb.R

/**
 * CCTV 直播画质档。
 *
 * 官方新版 liveplayer.js 已忽略 createLivePlayer 的 br 参数（2026-10 MuMu 实测：br=1080 与
 * br=360 拉到的都是同一条 720p 流）。真实机制：vdn 接口只下发一条 master 清单 URL（形如
 * `.../index.m3u8?b=200-2100`），CDN 按该 `b=` 码率窗口(kbps)过滤 master 里的变体
 * （BANDWIDTH 落窗才保留；窗口为空时返回全量变体）。因此切画质 = 改写 master
 * 请求的 `b=` 窗口，让清单只含目标档变体，MSE 播放器自然锁档。
 *
 * 各档窗口经 cctv1/cctv13 实测标定（CDN 变体为 1800k/1350k/900k/600k）。
 *
 * 2026-11-26 实测补充（issue#132）：**不同频道的变体真实分辨率并不一致**，且存在假档——
 * - 央3/央6/央8 的 vdn 原始窗口只有 `b=200-1200`（服务端只配两档），其 `td(1800k/1280x720)`
 *   与 `hd(900k/854x480)` 返回的同一分片字节数完全相同，即 720P 只是 480P 流的别名；
 * - 央1/央2/央4/央7/央9-17 的 `pd(3200k/1920x1080)` 分片与 600k 分片等长（实为 360P），
 *   只有 CCTV-5 的 pd 是真 1080P（分片 1.5MB vs 720P 0.8MB），但 3200k 不在 P1080 窗口内
 *   会被过滤。
 * 因此档位文案（[displayNameRes]）统一改为相对档位「超清/高清/标清/流畅」，
 * 不再对具体分辨率作承诺；[label] 仅作日志与持久化稳定键（沿用历史值，兼容旧存储）。
 */
enum class CctvLiveQuality(
    /** 稳定键：日志与持久化（跨语言/跨版本不变，沿用历史值"1080P"等以兼容旧存储）。 */
    val label: String,
    /** 菜单显示文案（本地化、相对档位；源端分辨率逐频道变化，不对具体分辨率作承诺）。 */
    @StringRes val displayNameRes: Int,
    /** master 清单请求的 `b=` 码率窗口（kbps 低-高），窗口内恰含本档唯一变体。 */
    val bRange: String,
    /** [com.mytvb.feature.marmot.domain.HzItem.level] 档位匹配与显示排序用数值。 */
    val level: Int
) {
    P1080("1080P", R.string.cctv_quality_best, "1800-3000", 1080),
    P720("720P", R.string.cctv_quality_high, "1400-2100", 720),
    P540("540P", R.string.cctv_quality_medium, "1200-1400", 540),
    P360("360P", R.string.cctv_quality_low, "200-600", 360);

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
