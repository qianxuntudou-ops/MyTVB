package com.mytvb.feature.marmot

import java.net.URLEncoder

/**
 * CCTV 直播台标（channel logo）映射器。
 *
 * 台标图全量托管在**我们自己的 GitHub 仓库**（本仓库 `logos/` 目录，929 张 / 5.5MB，
 * 128 色 png）。来源：fanmingming/live 仓库 tv/ 目录（MIT）全量备份，2026-11-19 搬入——
 * 原图床 live.fanmingming.com 在国内多处被 DNS 污染（解析到 Facebook 段假 IP，连接超时），
 * 备份到自家仓库后不再受上游生死影响。
 *
 * 运行时走 git 镜像代理加载（国内可达）：
 * `https://ghfast.top/https://raw.githubusercontent.com/qianxuntudou-ops/MyTVB/main/logos/{id}.png`
 * 备选 CDN：`https://cdn.jsdelivr.net/gh/qianxuntudou-ops/MyTVB@main/logos/{id}.png`
 * （加载失败一律隐藏台标，不影响播放；ImageLoader LruCache + 磁盘缓存兜住重复加载）
 *
 * 补充/更新台标：往 logos/ 加文件（文件名 = [logoIdFor] 返回值 + ".png"），push 即生效。
 * 原始图处理流程：180px 宽缩放 + 128 色量化（全量 26MB → 5.5MB，纯色图形视觉无损）。
 *
 * 映射规则按 2026-11-19 对 tv2.json 全部 772 个频道名实测校准：
 * - CCTV 系 41/42 命中、省卫视系 44/46 命中，其余地方台/专题频道查全量库（多数有图）
 */
object CctvChannelLogo {

    /** 主源：ghfast 镜像代理 + 本仓库 raw（ghfast 为公共 GitHub 加速代理，项目备用源手册已验证）。 */
    private const val BASE_URL =
        "https://ghfast.top/https://raw.githubusercontent.com/qianxuntudou-ops/MyTVB/main/logos/"

    /** 已知命名差异别名（频道名去空白后 → 标准台名）。按 tv2.json 实测死链逐条补齐。 */
    private val ALIASES = mapOf(
        "上海东方卫视" to "东方卫视",
        "福建东南卫视" to "东南卫视",
        "四川康巴卫视" to "康巴卫视",
        "新建兵团卫视" to "兵团卫视",
        "齐鲁卫视" to "山东卫视", // 齐鲁卫视为山东广播电视台旗下，无独立图
    )

    /**
     * 频道名 → 台标 URL；无法映射返回 null（调用方隐藏台标）。
     * 文件名走 URL 编码：`CCTV5+` 的 `+` 必须编成 `%2B`（裸 + 在 URL query 语义是空格），
     * 中文按 UTF-8 percent-encode（raw/github 均接受）。
     */
    fun logoUrlFor(channelName: String): String? {
        val id = logoIdFor(channelName) ?: return null
        val enc = URLEncoder.encode(id, "UTF-8").replace("+", "%2B")
        return BASE_URL + enc + ".png"
    }

    /** 频道名 → 标准台名（logos/ 文件名主体，不含 .png）。测试用。 */
    internal fun logoIdFor(channelName: String): String? {
        val n = channelName.trim()
        if (n.isEmpty()) return null
        // CCTV 系：「CCTV-1 综合」「CCTV1」「CCTV-5+ 体育赛事」→ CCTV1 / CCTV5+。
        // 数字后紧跟 K 的排除（CCTV8K/CCTV4K 是超清组频道，误匹配成 CCTV8/CCTV4 台标属张冠李戴，
        // 无对应图，返回 null 不显示）
        Regex("""^CCTV\s*-?\s*(\d+)(?![Kk])\s*(\+)?""", RegexOption.IGNORE_CASE).find(n)?.let { m ->
            return "CCTV${m.groupValues[1]}" + (m.groupValues[2].ifEmpty { "" })
        }
        // CGTN 系：主频道与纪录频道有图，语种频道无图
        val compact = n.replace(Regex("[\\s　]+"), "")
        if (compact.equals("CGTN", ignoreCase = true)) return "CGTN"
        if (compact == "CGTN纪录" || compact == "CGTN纪录频道") return "CGTN纪录"
        // 卫视系：标准台名直接用，带省份前缀的查别名表
        if (compact.endsWith("卫视")) {
            return ALIASES[compact] ?: compact
        }
        // 地方台/专题频道：按全量库文件名直查（tv2.json 频道名多数与库内标准台名一致）
        return compact
    }
}
