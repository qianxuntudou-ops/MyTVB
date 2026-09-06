package com.tutu.myblbl.core.common.content

/**
 * 多模式子串匹配（Aho-Corasick 自动机）。
 *
 * 一次线性扫描文本即可判断是否包含词表中任意关键词，与
 * `keywords.any { text.contains(it) }` 语义完全等价，但复杂度从
 * 词数 × 文本长度 降为 文本长度。词表构建后不可变，适合内容过滤
 * 这类静态关键词集 + 高频文本扫描的场景（400+ 词 × 每条视频标题/简介，
 * 朴素 contains 实测 22 条视频要 50~60ms，自动机 <1ms）。
 */
internal class AhoCorasick(keywords: Collection<String>) {

    private val children = ArrayList<HashMap<Char, Int>>()
    private val fail = ArrayList<Int>()
    private val matched = ArrayList<Boolean>()

    init {
        // root = 0，三个数组按下标对齐
        children.add(HashMap())
        fail.add(0)
        matched.add(false)
        keywords.forEach { keyword ->
            if (keyword.isEmpty()) return@forEach
            var node = 0
            for (c in keyword) {
                val existing = children[node][c]
                if (existing != null) {
                    node = existing
                    continue
                }
                val id = children.size
                children.add(HashMap())
                fail.add(0)
                matched.add(false)
                children[node][c] = id
                node = id
            }
            matched[node] = true
        }
        buildFailLinks()
    }

    private fun buildFailLinks() {
        val queue = ArrayDeque<Int>()
        children[0].values.forEach { child ->
            queue.add(child)
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            children[node].forEach { (c, child) ->
                var fallback = fail[node]
                var next = children[fallback][c]
                while (fallback != 0 && next == null) {
                    fallback = fail[fallback]
                    next = children[fallback][c]
                }
                fail[child] = if (next != null && next != child) next else 0
                // 聚合 fail 链上的词尾标记，匹配时只需查当前节点
                matched[child] = matched[child] || matched[fail[child]]
                queue.add(child)
            }
        }
    }

    /** 文本是否包含词表中任意关键词。 */
    fun containsAny(text: String): Boolean {
        if (text.isEmpty()) return false
        var node = 0
        for (c in text) {
            var next = children[node][c]
            while (next == null && node != 0) {
                node = fail[node]
                next = children[node][c]
            }
            node = next ?: 0
            if (matched[node]) return true
        }
        return false
    }
}
