package com.mytvb.feature.cctv.cmg

/**
 * 央视频（yangshipin）native 库桥（libcmg_yangshipin.so）。
 *
 * 一个 so 内含两套 wasm 运行时（符号隔离见 cpp/cmg/CMakeLists 注释）：
 * - CMG 解密（hls.cmg.js 的 wasm）：有状态 NALU 级段解密，[decryptLiveSegment]
 *   每段整体重启 runtime 后段内逐 NAL 推进（服务器校准时钟 + UpdatePlayer）；
 * - ysp 签名（get_live_info 的 wasm）：[tokenRnd]/[signature] 供三步签名链。
 *
 * 段解密全程持 C 层全局锁（状态机单线程），宿主并行预取分片时会自然排队。
 */
object CmgNativeBridge {

    init {
        System.loadLibrary("cmg_yangshipin")
    }

    /** get_live_info 签名链 step2：open/token 的 rnd 参数。 */
    external fun tokenRnd(guid: String, timestampMs: String): String?

    /** get_live_info 签名链 step3：yspsdksign 的 wasm 段。 */
    external fun signature(guid: String, token: String, input: String): String?

    /**
     * 配置频道会话（切台时调用）：
     * @param playerTag native-light 自造毫秒时间戳（服务器校准时间），InitPlayer/UpdatePlayer 用
     * @param clockOffsetMs 服务器时间 - 本地时间（auth 响应 data.ts 校准）
     * @param locationHref 播放页 URL（wasm 环境读取 location.href）
     */
    external fun configureSession(playerTag: String, clockOffsetMs: Long, locationHref: String)

    /** 会话整体复位（含 forced tag/时钟），切出 CMG 频道时调用。 */
    external fun resetSession()

    /**
     * 段级解密入口：原地改写 TS 分片（视频 PID 的受控 NAL 替换 + continuity 重排）。
     * 每段内部先整体重启 wasm runtime；无视频 PID 的段透传。
     * @return false 仅当会话未配置（未 configureSession）。
     */
    external fun decryptLiveSegment(transportStream: ByteArray): Boolean
}
