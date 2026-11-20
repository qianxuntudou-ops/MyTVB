package com.mytvb.feature.hongguo.net

import android.content.Context
import com.mytvb.core.common.log.AppLog
import org.json.JSONObject

/**
 * 签名链版本档案：内置枚举与远程热更档案共用此形状（code 进 query 五处 + UA，
 * versionName 进 Ladon/Argus 签名）。HongguoClient 签名链参数按本接口收口，
 * 内置 [HongguoClient.HongguoApiVersion] 枚举与远程覆写档案（version.json
 * `hg_versions` 字段下发）同构替换，红果改版本号不再需要发 APK。
 */
interface HongguoVersionRef {
    val code: String
    val versionName: String
}

/**
 * 红果接口版本档案热更（对标短剧盒 StrategyMagic 思路，2026-11-20）：
 * version.json 新增可选 `hg_versions` 字段，启动检查更新时随三级容错链捎带下发，
 * 合法档案覆写内存+落盘，下次启动红果首请求前即恢复生效；非法/缺失不动现有值。
 *
 * 两个档位独立覆写：
 *  - `default`：全通道默认（内置 74532 / 7.4.5.32）；
 *  - `plan`：plan/v1 相关作品通道（内置 72232 / 7.2.2.32——服务端按 version_code
 *    下发不同 cell 结构，73532 只回「猜你喜欢」，72232 才回「相关作品」，故独立钉版）。
 *
 * 校验：code 5~6 位纯数字、versionName 四段点分数字，任一非法整档丢弃（防投毒/手滑）。
 * 写入时机竞态无害：applyFrom 同步写 prefs+内存，restore 读 prefs，任何顺序最终一致。
 */
internal object HongguoVersionProfiles {

    private const val TAG = "HgVersionProfiles"
    private const val PREFS = "hongguo_remote_config"
    private const val K_DEFAULT_CODE = "default_code"
    private const val K_DEFAULT_NAME = "default_name"
    private const val K_PLAN_CODE = "plan_code"
    private const val K_PLAN_NAME = "plan_name"

    /** 覆写档案值（与内置档案同构，仅内存态）。 */
    data class Profile(override val code: String, override val versionName: String) :
        HongguoVersionRef

    val default: HongguoVersionRef get() = defaultOverride ?: BUILTIN_DEFAULT
    val plan: HongguoVersionRef get() = planOverride ?: BUILTIN_PLAN

    @Volatile private var defaultOverride: Profile? = null
    @Volatile private var planOverride: Profile? = null

    /**
     * 启动恢复（红果首请求前，挂 HongguoRepository init）：载入上次远程下发的档案。
     * 读失败/无值 = 保持内置，行为与未热更完全一致。
     */
    fun restore(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val def = prefs.getString(K_DEFAULT_CODE, null)
                ?.let { code -> Profile(code, prefs.getString(K_DEFAULT_NAME, "") ?: "") }
                ?.takeIf { isValid(it) }
            val pl = prefs.getString(K_PLAN_CODE, null)
                ?.let { code -> Profile(code, prefs.getString(K_PLAN_NAME, "") ?: "") }
                ?.takeIf { isValid(it) }
            if (def != null && def != defaultOverride) defaultOverride = def
            if (pl != null && pl != planOverride) planOverride = pl
            if (def != null || pl != null) {
                AppLog.i(TAG, "restore default=${(def ?: default).code} plan=${(pl ?: plan).code}")
            }
        }.onFailure { AppLog.w(TAG, "restore failed: ${it.message}") }
    }

    /**
     * 远程下发入口（version.json `hg_versions`）：合法档覆写内存+落盘，非法档忽略。
     * 整体缺失/为 null 不动现有值（热更字段是可选的，老 version.json 兼容）。
     */
    fun applyFrom(context: Context, json: JSONObject?) {
        if (json == null) return
        val def = parseProfile(json.optJSONObject("default"))
        val pl = parseProfile(json.optJSONObject("plan"))
        if (def == null && pl == null) {
            AppLog.w(TAG, "applyFrom: hg_versions 无合法档案，忽略")
            return
        }
        def?.let { defaultOverride = it }
        pl?.let { planOverride = it }
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
            def?.let { prefs.putString(K_DEFAULT_CODE, it.code).putString(K_DEFAULT_NAME, it.versionName) }
            pl?.let { prefs.putString(K_PLAN_CODE, it.code).putString(K_PLAN_NAME, it.versionName) }
            prefs.apply()
        }.onFailure { AppLog.w(TAG, "persist failed: ${it.message}") }
        AppLog.i(TAG, "applyFrom default=${def?.code ?: "(keep)"} plan=${pl?.code ?: "(keep)"}")
    }

    /** 单档解析+校验：字段缺失/格式非法返回 null（不部分采用）。纯函数，可单测。 */
    fun parseProfile(obj: JSONObject?): Profile? {
        if (obj == null) return null
        val code = obj.optString("code").trim()
        val name = obj.optString("name").trim()
        val profile = Profile(code, name)
        return profile.takeIf { isValid(it) }
    }

    /** code：5~6 位纯数字；versionName：四段点分数字（红果 7.4.5.32 形态）。 */
    private fun isValid(p: Profile): Boolean =
        p.code.matches(Regex("\\d{5,6}")) &&
            p.versionName.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))

    private val BUILTIN_DEFAULT = Profile("74532", "7.4.5.32")
    private val BUILTIN_PLAN = Profile("72232", "7.2.2.32")

    /** 仅测试用：清空内存覆写，回落内置档案。 */
    internal fun resetForTest() {
        defaultOverride = null
        planOverride = null
    }
}
