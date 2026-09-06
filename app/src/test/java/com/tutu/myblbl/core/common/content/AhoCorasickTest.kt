package com.tutu.myblbl.core.common.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Aho-Corasick 自动机与朴素 `keywords.any { text.contains(it) }` 的等价性验证。
 * ContentFilter 的内容过滤语义完全依赖这一等价关系，改动自动机实现前必须保持本测试通过。
 */
class AhoCorasickTest {

    private fun naive(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    private fun assertEquivalent(keywords: List<String>, samples: List<String>) {
        val automaton = AhoCorasick(keywords)
        samples.forEach { sample ->
            assertEquals("mismatch on: $sample", naive(sample, keywords), automaton.containsAny(sample))
        }
    }

    @Test
    fun `空词表永不命中`() {
        val automaton = AhoCorasick(emptyList())
        assertFalse(automaton.containsAny("任意文本"))
        assertFalse(automaton.containsAny(""))
    }

    @Test
    fun `空文本与空白文本不命中`() {
        assertEquivalent(
            keywords = listOf("色情", "asmr"),
            samples = listOf("", " ", "　", "\n\t")
        )
    }

    @Test
    fun `精确命中与未命中`() {
        // 模拟 ContentFilter 约定：词表与文本均已在构造前 lowercase
        val keywords = listOf("色情", "asmr", "r18", "jk")
        val automaton = AhoCorasick(keywords)
        assertTrue(automaton.containsAny("这是一个色情内容"))
        assertTrue(automaton.containsAny("asmr助眠"))
        assertTrue(automaton.containsAny("标题含 r18 词"))
        assertTrue(automaton.containsAny("她是jk吗"))
        assertFalse(automaton.containsAny("正常视频标题"))
        assertFalse(automaton.containsAny("as mr")) // 词中间断开不算命中
    }

    @Test
    fun `命中在文本首尾`() {
        val keywords = listOf("福利", "惊悚")
        val automaton = AhoCorasick(keywords)
        assertTrue(automaton.containsAny("福利来了"))
        assertTrue(automaton.containsAny("深夜福利"))
        assertTrue(automaton.containsAny("福利"))
    }

    @Test
    fun `前缀与重叠词`() {
        // "福利"是"粉丝福利"的前缀子串；"bc"与"ab"在"abc"中重叠
        assertEquivalent(
            keywords = listOf("福利", "粉丝福利", "ab", "bc", "a"),
            samples = listOf("abc", "粉丝福利放送", "ab", "bc", "c", "xyz", "aabcc")
        )
    }

    @Test
    fun `一个词是另一个词的子串`() {
        assertEquivalent(
            keywords = listOf("丝", "黑丝", "黑丝袜"),
            samples = listOf("黑丝袜", "丝袜", "粉丝", "螺丝钉", "黑丝 jio", "丝绸")
        )
    }

    @Test
    fun `中文英文数字混合`() {
        assertEquivalent(
            keywords = listOf("18禁", "r-18", "lap dance", "tw erking", "ｓｅｘｙ"),
            samples = listOf("这是18禁视频", "r-18资源", "lap dance教学", "tw erking合集", "全角ｓｅｘｙ", "正常内容1830")
        )
    }

    @Test
    fun `随机文本随机词表模糊对比`() {
        val alphabet = ('a'..'z') + listOf('鬼', '血', '丝', '舞', '惊', '恐', '美', '女')
        val random = Random(seed = 20260906)
        repeat(20) { round ->
            val keywords = buildList {
                repeat(30) {
                    val len = random.nextInt(1, 4)
                    buildString { repeat(len) { append(alphabet.random(random)) } }
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }.distinct()
            val samples = buildList {
                repeat(200) {
                    buildString { repeat(random.nextInt(0, 12)) { append(alphabet.random(random)) } }
                        .let(::add)
                }
            }
            assertEquivalent(keywords, samples)
        }
    }

    @Test
    fun `真实规模词表下快速命中`() {
        // 模拟 ContentFilter 真实使用规模：数百关键词
        val keywords = (1..400).map { "关键词$it" } + listOf("命中词")
        val automaton = AhoCorasick(keywords)
        assertTrue(automaton.containsAny("前缀命中词后缀"))
        assertFalse(automaton.containsAny("完全无关的文本"))
    }
}
