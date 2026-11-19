package com.mytvb.feature.marmot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CctvChannelLogo] 频道名 → 台标 URL 映射规则。
 * 用例命名取自 tv2.json 真实频道名；台标库为本仓库 logos/ 全量备份（929 张）。
 */
class CctvChannelLogoTest {

    @Test
    fun `CCTV系 带编号与中文描述`() {
        assertEquals("CCTV1", CctvChannelLogo.logoIdFor("CCTV-1 综合"))
        assertEquals("CCTV13", CctvChannelLogo.logoIdFor("CCTV-13 新闻"))
        assertEquals("CCTV2", CctvChannelLogo.logoIdFor("CCTV-2 财经"))
        assertEquals("CCTV5+", CctvChannelLogo.logoIdFor("CCTV-5+ 体育赛事"))
        assertEquals("CCTV5+", CctvChannelLogo.logoIdFor("CCTV5+ 体育赛事"))
    }

    @Test
    fun `CCTV系 纯编号无描述`() {
        assertEquals("CCTV1", CctvChannelLogo.logoIdFor("CCTV1"))
        assertEquals("CCTV4", CctvChannelLogo.logoIdFor("CCTV-4"))
    }

    @Test
    fun `超清组4K8K 走全量库直查非普通台号`() {
        // 全量库里有 CCTV8K.png/CCTV4K.png 真图——「CCTV8K」不得误匹配成 CCTV8（张冠李戴），
        // 应落到全量直查返回自身 id
        assertEquals("CCTV8K", CctvChannelLogo.logoIdFor("CCTV8K"))
        assertEquals("CCTV4K", CctvChannelLogo.logoIdFor("CCTV4K"))
        // 「CCTV16 4K」数字后是空格，正常匹配 CCTV16
        assertEquals("CCTV16", CctvChannelLogo.logoIdFor("CCTV16 4K"))
    }

    @Test
    fun `CGTN系 主频道与纪录频道`() {
        assertEquals("CGTN", CctvChannelLogo.logoIdFor("CGTN"))
        assertEquals("CGTN", CctvChannelLogo.logoIdFor("cgtn"))
        assertEquals("CGTN纪录", CctvChannelLogo.logoIdFor("CGTN纪录频道"))
    }

    @Test
    fun `卫视系 标准台名直接用`() {
        assertEquals("湖南卫视", CctvChannelLogo.logoIdFor("湖南卫视"))
        assertEquals("江苏卫视", CctvChannelLogo.logoIdFor("江苏卫视"))
        assertEquals("东方卫视", CctvChannelLogo.logoIdFor("东方卫视"))
    }

    @Test
    fun `卫视系 带省份前缀走别名表`() {
        assertEquals("东方卫视", CctvChannelLogo.logoIdFor("上海东方卫视"))
        assertEquals("东南卫视", CctvChannelLogo.logoIdFor("福建东南卫视"))
        assertEquals("康巴卫视", CctvChannelLogo.logoIdFor("四川康巴卫视"))
        assertEquals("兵团卫视", CctvChannelLogo.logoIdFor("新建兵团卫视"))
        assertEquals("山东卫视", CctvChannelLogo.logoIdFor("齐鲁卫视"))
    }

    @Test
    fun `URL拼接 镜像代理地址与加号编码`() {
        assertEquals(
            "https://ghfast.top/https://raw.githubusercontent.com/qianxuntudou-ops/MyTVB/main/logos/CCTV1.png",
            CctvChannelLogo.logoUrlFor("CCTV-1 综合")
        )
        // CCTV5+ 的 + 必须编码为 %2B（裸 + 在 URL 里是空格语义，raw 会 404）
        assertEquals(
            "https://ghfast.top/https://raw.githubusercontent.com/qianxuntudou-ops/MyTVB/main/logos/CCTV5%2B.png",
            CctvChannelLogo.logoUrlFor("CCTV-5+ 体育赛事")
        )
        assertEquals(
            "https://ghfast.top/https://raw.githubusercontent.com/qianxuntudou-ops/MyTVB/main/logos/%E6%B9%96%E5%8D%97%E5%8D%AB%E8%A7%86.png",
            CctvChannelLogo.logoUrlFor("湖南卫视")
        )
    }

    @Test
    fun `空白输入`() {
        assertNull(CctvChannelLogo.logoIdFor(""))
        assertNull(CctvChannelLogo.logoIdFor("   "))
    }
}
