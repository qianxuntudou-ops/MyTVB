package com.tutu.myblbl.feature.player.danmaku.common

import java.util.Locale
import java.util.zip.CRC32

/**
 * B 站云同步的用户弹幕屏蔽规则（用户在官方客户端/网页设置的屏蔽词、正则、拉黑用户）。
 *
 * 数据源 x/dm/filter/user（需登录态），拉取见 [DanmakuUserFilterRepository]。
 * blockedUserMidHashes 存 CRC32(mid) 的 8 位小写 hex，与弹幕 proto 的 midHash 同口径。
 */
internal data class DanmakuUserFilter(
    val keywords: List<String> = emptyList(),
    val regexes: List<Regex> = emptyList(),
    val blockedUserMidHashes: Set<String> = emptySet(),
) {
    fun isEmpty(): Boolean = keywords.isEmpty() && regexes.isEmpty() && blockedUserMidHashes.isEmpty()

    fun isNotEmpty(): Boolean = !isEmpty()

    companion object {
        val EMPTY = DanmakuUserFilter()

        /** mid → 与弹幕 proto midHash 同口径的 CRC32 hex（8 位小写，补前导零）。 */
        fun midHashOfMid(mid: Long): String {
            val crc = CRC32()
            crc.update(mid.toString().toByteArray(Charsets.UTF_8))
            return java.lang.Long.toHexString(crc.value).padStart(8, '0')
        }

        /**
         * 归一化服务器返回的 type=2（屏蔽用户）规则：
         * - 8 位 hex 串（可能丢前导零）→ 小写补零；
         * - 纯数字且长度 > 8 视为 MID（历史数据），转 CRC32 hash。
         */
        fun normalizeMidHashRule(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            if (MID_REGEX.matches(trimmed)) {
                val mid = trimmed.toLongOrNull()?.takeIf { it > 0L } ?: return null
                return if (trimmed.length > 8) midHashOfMid(mid) else trimmed.lowercase(Locale.US).padStart(8, '0')
            }
            if (MID_HASH_REGEX.matches(trimmed)) return trimmed.lowercase(Locale.US).padStart(8, '0')
            return null
        }

        /** 归一化 type=1（正则）规则：支持 "/pattern/flags" 字面量（i/m/s），非法正则返回 null。 */
        fun normalizeRegexRule(raw: String): Regex? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null

            val literal = parseRegexLiteral(trimmed)
            val pattern = literal?.first ?: trimmed
            val options = literal?.second ?: emptySet()

            return runCatching { Regex(pattern, options) }.getOrNull()
        }

        private const val MID_REGEX_STR = "^[0-9]{1,20}$"
        private const val MID_HASH_REGEX_STR = "^[0-9a-fA-F]{1,8}$"
        private val MID_REGEX = Regex(MID_REGEX_STR)
        private val MID_HASH_REGEX = Regex(MID_HASH_REGEX_STR)

        /** "/pattern/flags" → (pattern, options)；非该形态返回 null。 */
        private fun parseRegexLiteral(raw: String): Pair<String, Set<RegexOption>>? {
            if (!raw.startsWith("/")) return null
            val closing = findLastUnescapedSlash(raw) ?: return null
            if (closing <= 0) return null

            val pattern = raw.substring(1, closing)
            val flags = raw.substring(closing + 1)
            if (flags.isBlank()) return pattern to emptySet()

            val options = LinkedHashSet<RegexOption>()
            for (c in flags) {
                when (c) {
                    'i' -> options.add(RegexOption.IGNORE_CASE)
                    'm' -> options.add(RegexOption.MULTILINE)
                    's' -> options.add(RegexOption.DOT_MATCHES_ALL)
                    else -> continue
                }
            }
            return pattern to options
        }

        private fun findLastUnescapedSlash(raw: String): Int? {
            for (i in raw.length - 1 downTo 1) {
                if (raw[i] != '/') continue
                var backslashes = 0
                var j = i - 1
                while (j >= 0 && raw[j] == '\\') {
                    backslashes++
                    j--
                }
                if (backslashes % 2 == 0) return i
            }
            return null
        }
    }
}
