package com.mytvb.feature.marmot

import com.mytvb.feature.marmot.domain.MarmotModels.Vod

/**
 * 央视频超清补充频道（代码化兜底，issue#106 频道补全）。
 *
 * 背景：tv-web 资源（含频道表 tv2.json）沿用第三方云端（api.vonchange.com，utao 项目）
 * 热更新，filesDir 优先读取——云端任何一次新版本都会把 assets 里的频道表补全冲掉。
 * 因此把「央视频超清」组（CCTV 4K/8K、CGTN 六语种、数字剧场频道）的补充频道硬编码
 * 在本表，由 [MarmotLiveData.load] 在解析频道表后合并（按 URL 去重，幂等）——
 * 无论 tv2.json 来自 assets 还是云端覆盖，这批频道始终在列。
 *
 * pid 为央视频平台频道号（与 TvWasm/cjs 的 cmg.m3u 同源）；能否播出 4K/8K 由设备自决。
 */
object CctvExtraChannels {

    /** 补充频道合并进的目标组名（频道表中该组不存在时新建）。 */
    const val GROUP_NAME = "央视频超清"

    /** 4K/8K 档置顶展示。 */
    val EXTRA_CHANNELS: List<Vod> = listOf(
        Vod(name = "CCTV8K", url = "https://www.yangshipin.cn/tv/home?pid=600156816"),
        Vod(name = "CCTV4K", url = "https://www.yangshipin.cn/tv/home?pid=600002264"),
        Vod(name = "CCTV16 4K", url = "https://www.yangshipin.cn/tv/home?pid=600099502"),
        Vod(name = "CGTN", url = "https://www.yangshipin.cn/tv/home?pid=600014550"),
        Vod(name = "CGTN纪录频道", url = "https://www.yangshipin.cn/tv/home?pid=600084781"),
        Vod(name = "CGTN法语频道", url = "https://www.yangshipin.cn/tv/home?pid=600084704"),
        Vod(name = "CGTN俄语频道", url = "https://www.yangshipin.cn/tv/home?pid=600084758"),
        Vod(name = "CGTN西班牙语频道", url = "https://www.yangshipin.cn/tv/home?pid=600084744"),
        Vod(name = "CGTN阿拉伯语频道", url = "https://www.yangshipin.cn/tv/home?pid=600084782"),
        Vod(name = "CCTV 风云剧场", url = "https://www.yangshipin.cn/tv/home?pid=600099658"),
        Vod(name = "CCTV 第一剧场", url = "https://www.yangshipin.cn/tv/home?pid=600099655"),
        Vod(name = "CCTV 怀旧剧场", url = "https://www.yangshipin.cn/tv/home?pid=600099620")
    )
}
