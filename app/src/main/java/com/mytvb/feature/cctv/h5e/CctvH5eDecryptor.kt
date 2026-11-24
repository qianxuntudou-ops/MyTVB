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

    /**
     * 解密一段 CCTV 影视点播（getHttpVideoInfo 的 hls_h5e_url）TS 分片。
     * 与直播同一 wasm：差异仅 mediaTag（player_container_player，slice NAL 带
     * "##1000000##0" 后缀）与 NAL25 加密标志探测（cctv-h5e-decrypt 的 GUID 约定）。
     * 段内无加密 NAL（未加密老剧）时原样透传而非拒绝。
     */
    external fun decryptVodTransportStream(transportStream: ByteArray): ByteArray?

    /** SPS 兼容模式：部分老盒子解码器对解密后 SPS 严格校验失败时开启。 */
    external fun setSpsCompatibilityMode(enabled: Boolean)

    /** 切台/停止时取消进行中的解密（解密线程尽快返回）。 */
    external fun cancelPendingDecrypts()

    /**
     * 释放**调用线程**的 wasm 上下文。
     *
     * 运行时状态是 `__thread`、线性内存是 `calloc`（开播时撑到 96MB 上限），
     * 所以只有「在这条线程上调用」才有效——在主线程调是空操作。跑解密的线程
     * （media3 加载线程、预取器的 IO 线程）由各自框架管理，项目里拿不到它们的
     * 退出时机，因此原生侧另有 pthread key 析构兜底（线程退出时自动释放）；
     * 预取器则在每个解密任务结束后主动调用本方法，避免常驻线程池留住上下文。
     */
    external fun releaseThreadContext()
}
