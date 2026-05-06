package com.tutu.myblbl.network.security

import java.security.MessageDigest

object AppSignUtils {
    const val TV_APP_KEY = "dfca71928277209b"
    private const val TV_APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e"

    fun signForTvLogin(params: Map<String, String>): Map<String, String> {
        return sign(params, TV_APP_SEC)
    }

    fun signForAppApi(params: Map<String, String>): Map<String, String> {
        return sign(params, TV_APP_SEC)
    }

    private fun sign(params: Map<String, String>, appSec: String): Map<String, String> {
        val sortedParams = params.toSortedMap()
        val queryString = sortedParams.entries.joinToString("&") {
            "${percentEncode(it.key)}=${percentEncode(it.value)}"
        }
        val sign = md5(queryString + appSec)
        return sortedParams + ("sign" to sign)
    }

    fun getTimestamp(): Long = System.currentTimeMillis() / 1000

    private fun percentEncode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
                c in '0'.code..'9'.code || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c ushr 4])
                sb.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
