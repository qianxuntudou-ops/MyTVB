package com.mytvb.feature.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.settings.AudioBalanceLevel
import com.mytvb.feature.player.settings.AudioBalanceSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PCM 级自适应响度归一（移植自 blbl 的 VolumeBalanceAudioProcessor）。
 *
 * 播放前几秒校准出节目 RMS，之后持续积分跟踪，向目标 RMS 收敛；
 * 增益钳制在档位区间内，每块缓冲做峰值保护防削波，增益升降各有
 * attack/release 时间常数防 pumping。挂在 DefaultAudioSink 的
 * AudioProcessor 链上，不经过设备 audiofx 驱动，无"电音"失真风险。
 *
 * 档位读全局 [AudioBalanceSettings]，设置页切换后下一个音频块即生效，
 * 无需重建播放器。关档走 passthrough。
 */
@UnstableApi
internal class VolumeBalanceAudioProcessor : BaseAudioProcessor() {

    private var level: AudioBalanceLevel = AudioBalanceSettings.level
    private var params: Params = paramsFor(level)

    private var sampleRateHz: Int = 48_000
    private var channelCount: Int = 2
    private var inputEncoding: Int = C.ENCODING_PCM_16BIT
    private var signalAccumulatedSec: Double = 0.0
    private var calibrationSumSquares: Double = 0.0
    private var calibrationSampleCount: Long = 0L
    private var programMeanSquare: Double? = null
    private var currentGain: Float = 1.0f

    init {
        resetAdaptiveState(keepPrior = false)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // 档位被外部（设置页）切换：换参数并重置自适应状态，切换即时生效。
        val globalLevel = AudioBalanceSettings.level
        if (globalLevel != level) {
            level = globalLevel
            params = paramsFor(level)
            resetAdaptiveState(keepPrior = false)
        }

        if (!inputBuffer.hasRemaining()) return

        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        out.order(ByteOrder.nativeOrder())

        val currentLevel = level
        if (currentLevel == AudioBalanceLevel.OFF || !isActive) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val p = params
        val bytesPerSample =
            when (inputEncoding) {
                C.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }

        val inBuf = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val startPos = inBuf.position()
        val endPos = inBuf.limit()

        val sampleCount = (endPos - startPos) / bytesPerSample
        if (sampleCount <= 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        var sumSquares = 0.0
        var peakAbs = 0.0
        when (inputEncoding) {
            C.ENCODING_PCM_FLOAT -> {
                while (inBuf.remaining() >= 4) {
                    val f = inBuf.getFloat().toDouble()
                    val a = abs(f)
                    peakAbs = max(peakAbs, a)
                    sumSquares += f * f
                }
            }

            else -> {
                while (inBuf.remaining() >= 2) {
                    val s = inBuf.getShort()
                    val v = s.toDouble() / 32768.0
                    val a = abs(v)
                    peakAbs = max(peakAbs, a)
                    sumSquares += v * v
                }
            }
        }
        val meanSquare = (sumSquares / sampleCount.toDouble()).coerceAtLeast(0.0)
        val rms = sqrt(meanSquare)
        val rmsDb = if (rms > 0.0) (20.0 * log10(rms)) else -120.0

        val sr = sampleRateHz.coerceAtLeast(8_000)
        val ch = channelCount.coerceAtLeast(1)
        val frameCount = max(1, sampleCount / ch)
        val dtSec = frameCount.toDouble() / sr.toDouble()

        if (rmsDb > p.silenceGateDb) {
            signalAccumulatedSec += dtSec
            if (programMeanSquare == null) {
                calibrationSumSquares += sumSquares
                calibrationSampleCount += sampleCount.toLong()
                if (signalAccumulatedSec >= p.calibrationSignalSec && calibrationSampleCount > 0L) {
                    programMeanSquare = calibrationSumSquares / calibrationSampleCount.toDouble()
                }
            } else {
                val prev = programMeanSquare ?: meanSquare
                val a = alphaForTimeConstant(dtSec, p.programIntegrationSec)
                programMeanSquare = prev + (a * (meanSquare - prev))
            }
        }

        val desiredGainDb =
            if (programMeanSquare == null) {
                p.startupGainDb
            } else {
                val programRms = sqrt((programMeanSquare ?: MIN_MEAN_SQUARE).coerceAtLeast(MIN_MEAN_SQUARE))
                val programRmsDb = 20.0 * log10(programRms)
                (TARGET_RMS_DB - programRmsDb).coerceIn(p.minGainDb, p.maxGainDb)
            }
        val desiredGainLinear = dbToLinear(desiredGainDb)

        val peakSafeGain =
            if (peakAbs > 0.0) {
                (LIMITER_CEILING / peakAbs).toFloat()
            } else {
                Float.POSITIVE_INFINITY
            }

        val desired = min(desiredGainLinear, peakSafeGain)
        val tau = if (desired < currentGain) p.gainAttackSec else p.gainReleaseSec
        val gainAlpha = alphaForTimeConstant(dtSec, tau).toFloat()
        currentGain = (currentGain + gainAlpha * (desired - currentGain)).coerceIn(p.minGainLinear, p.maxGainLinear)
        if (peakSafeGain.isFinite() && peakSafeGain < currentGain) {
            currentGain = peakSafeGain
        }

        val appliedGain = currentGain
        inBuf.position(startPos)
        when (inputEncoding) {
            C.ENCODING_PCM_FLOAT -> {
                while (inBuf.remaining() >= 4) {
                    val f = inBuf.getFloat()
                    val scaled = f * appliedGain
                    out.putFloat(scaled.coerceIn(-1.0f, 1.0f))
                }
            }

            else -> {
                while (inBuf.remaining() >= 2) {
                    val s = inBuf.getShort().toInt()
                    val scaled = (s.toFloat() * appliedGain).toInt()
                    val clipped = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(clipped.toShort())
                }
            }
        }

        inputBuffer.position(endPos)
        out.flip()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            -> {
                // 流重配诊断（低频，仅换流时一条）：gainBefore 可判重置行为——
                // keepPrior 生效时等于上一流稳态增益，未保留则等于 startup
                AppLog.d(
                    "AudioBalance",
                    "onConfigure sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                        "gainBefore=$currentGain"
                )
                inputEncoding = inputAudioFormat.encoding
                sampleRateHz = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 48_000
                channelCount = inputAudioFormat.channelCount.takeIf { it > 0 } ?: 2
                resetAdaptiveState(keepPrior = programMeanSquare != null)
                return inputAudioFormat
            }

            else -> return AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        AppLog.d("AudioBalance", "onFlush gainBefore=$currentGain")
        resetAdaptiveState(keepPrior = programMeanSquare != null)
    }

    override fun onReset() {
        AppLog.d("AudioBalance", "onReset gainBefore=$currentGain")
        resetAdaptiveState(keepPrior = false)
    }

    /**
     * [keepPrior]=true（跨流切换：上一节目已校准出响度）时保留 [programMeanSquare] 与
     * [currentGain]，只清校准/积分计数——消除切集瞬间「稳态压低增益 → startup」的音量
     * 跳变（用户反馈「切下一集音量突然变大然后逐渐恢复」：稳态被压 -9dB 的节目切集后
     * 校准期以 startup -1.5dB 播 2 秒，突跳 ~8dB，校准完 attack 再花 2 秒降回）。
     * 新集响度差异由 programIntegrationSec 积分快速收敛，峰值限幅（[LIMITER_CEILING]）
     * 兜底防削波。keepPrior=false（首次配置/档位切换/sink 释放）保持原 startup 行为。
     */
    private fun resetAdaptiveState(keepPrior: Boolean) {
        signalAccumulatedSec = 0.0
        calibrationSumSquares = 0.0
        calibrationSampleCount = 0L
        if (!keepPrior) {
            programMeanSquare = null
            currentGain = if (level == AudioBalanceLevel.OFF) 1.0f else params.startupGainLinear
        }
    }

    private data class Params(
        val startupGainDb: Double,
        val maxGainDb: Double,
        val minGainDb: Double,
        val silenceGateDb: Double,
        val calibrationSignalSec: Double,
        val programIntegrationSec: Double,
        val gainAttackSec: Double,
        val gainReleaseSec: Double,
        val startupGainLinear: Float,
        val maxGainLinear: Float,
        val minGainLinear: Float,
    )

    private companion object {
        private const val TARGET_RMS_DB = -20.0

        // Hard ceiling to avoid clipping.
        private const val LIMITER_CEILING = 0.98f
        private const val MIN_MEAN_SQUARE = 1.0e-12

        private fun paramsFor(level: AudioBalanceLevel): Params {
            // Start a little quieter, calibrate on the first few seconds of actual signal,
            // then only drift slowly so speech does not pump within a sentence.
            val startupGainDb =
                when (level) {
                    AudioBalanceLevel.HIGH -> -3.0
                    AudioBalanceLevel.MEDIUM -> -1.5
                    AudioBalanceLevel.LOW -> -0.5
                    AudioBalanceLevel.OFF -> 0.0
                }
            val maxGainDb =
                when (level) {
                    AudioBalanceLevel.HIGH -> 2.5
                    AudioBalanceLevel.MEDIUM -> 2.0
                    AudioBalanceLevel.LOW -> 1.0
                    AudioBalanceLevel.OFF -> 0.0
                }
            val minGainDb =
                when (level) {
                    AudioBalanceLevel.HIGH -> -16.0
                    AudioBalanceLevel.MEDIUM -> -12.0
                    AudioBalanceLevel.LOW -> -8.0
                    AudioBalanceLevel.OFF -> 0.0
                }

            val silenceGateDb =
                when (level) {
                    AudioBalanceLevel.HIGH -> -55.0
                    AudioBalanceLevel.MEDIUM -> -53.0
                    AudioBalanceLevel.LOW -> -50.0
                    AudioBalanceLevel.OFF -> -120.0
                }

            val calibrationSignalSec =
                when (level) {
                    AudioBalanceLevel.HIGH -> 1.5
                    AudioBalanceLevel.MEDIUM -> 2.0
                    AudioBalanceLevel.LOW -> 2.5
                    AudioBalanceLevel.OFF -> 0.0
                }

            val programIntegrationSec =
                when (level) {
                    AudioBalanceLevel.HIGH -> 6.0
                    AudioBalanceLevel.MEDIUM -> 8.0
                    AudioBalanceLevel.LOW -> 10.0
                    AudioBalanceLevel.OFF -> 0.0
                }

            val attackSec =
                when (level) {
                    AudioBalanceLevel.HIGH -> 1.5
                    AudioBalanceLevel.MEDIUM -> 2.0
                    AudioBalanceLevel.LOW -> 3.0
                    AudioBalanceLevel.OFF -> 0.05
                }

            val releaseSec =
                when (level) {
                    AudioBalanceLevel.HIGH -> 5.0
                    AudioBalanceLevel.MEDIUM -> 6.0
                    AudioBalanceLevel.LOW -> 8.0
                    AudioBalanceLevel.OFF -> 1.20
                }

            return Params(
                startupGainDb = startupGainDb,
                maxGainDb = maxGainDb,
                minGainDb = minGainDb,
                silenceGateDb = silenceGateDb,
                calibrationSignalSec = calibrationSignalSec,
                programIntegrationSec = programIntegrationSec,
                gainAttackSec = attackSec,
                gainReleaseSec = releaseSec,
                startupGainLinear = dbToLinear(startupGainDb),
                maxGainLinear = dbToLinear(maxGainDb),
                minGainLinear = dbToLinear(minGainDb),
            )
        }

        private fun dbToLinear(db: Double): Float {
            if (!db.isFinite()) return 1.0f
            return 10.0.pow(db / 20.0).toFloat()
        }

        private fun alphaForTimeConstant(dtSec: Double, tauSec: Double): Double {
            if (!dtSec.isFinite() || !tauSec.isFinite()) return 1.0
            if (dtSec <= 0.0) return 0.0
            if (tauSec <= 0.0) return 1.0
            val a = 1.0 - exp(-dtSec / tauSec)
            return a.coerceIn(0.0, 1.0)
        }
    }
}
