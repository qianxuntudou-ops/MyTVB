package com.mytvb.feature.cctv.h5e

/**
 * CCTV H5e 加密流原生解密桥（issue#106 阶段二）。
 *
 * 央视网直播/点播的 HLS（hls_cdrm）TS 分片经 H5e 加密（wasm 模块按 NALU 解密，
 * 密钥状态含播放器标签 h5player_player / H5PLAYER_CONFIG / pageHost）。
 * 原生层完成 wasm 执行 + TS demux/remux，Java 侧只需整段进整段出。
 *
 * native 实现见 app/src/main/cpp/cctv_h5e/；来源与协议说明见该处 CMakeLists 注释。
 */
object CctvH5eDecryptor {

    init {
        System.loadLibrary("cctv_h5e")
    }

    /**
     * 解密一段 TS 流（通常为 HLS 一个分片，188 字节对齐的 TS 包序列）。
     * 原地复用输入缓冲，返回同一字节数组（native 已改写内容）；
     * 解密失败（wasm trap / 会话未就绪）返回 null，调用方应丢弃该段并触发恢复。
     */
    external fun decryptTransportStream(transportStream: ByteArray): ByteArray?

    /** SPS 兼容模式：部分老盒子解码器对解密后 SPS 严格校验失败时开启。 */
    external fun setSpsCompatibilityMode(enabled: Boolean)

    /** 切台/停止时取消进行中的解密（解密线程尽快返回）。 */
    external fun cancelPendingDecrypts()

    /** 释放本线程的 wasm 上下文（播放线程退出时调用）。 */
    external fun releaseThreadContext()
}
