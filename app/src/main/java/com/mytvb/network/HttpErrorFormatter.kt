package com.mytvb.network

import android.content.Context
import com.mytvb.R
import retrofit2.HttpException

/**
 * 网络异常 → 用户可读文案（issue#156）。
 *
 * B 站网关风控会对个人空间类接口（收藏夹/稍后看/UP 主空间等）直接回 HTTP 412，
 * 此时无 JSON body 可解析，原样展示 e.message 只有一句 "HTTP 412"，
 * 用户无从理解也无处求助。网关 412 是账号级临时封禁，通常约 1 小时自动恢复，
 * 文案给出可操作的预期。其余异常原样透传，行为不变。
 *
 * 注意：业务码层的 -412 频控（JSON code）不走这里，由
 * [AuthErrorClassifier]/[RiskControlCooldownManager] 与各调用点自行处理。
 */
fun Throwable.userHttpMessage(context: Context): String {
    if (this is HttpException && code() == 412) {
        return context.getString(R.string.error_risk_control_412)
    }
    return message ?: context.getString(R.string.net_error)
}
