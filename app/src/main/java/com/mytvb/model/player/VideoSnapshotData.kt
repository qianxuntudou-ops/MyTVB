package com.mytvb.model.player

import com.google.gson.annotations.SerializedName
import kotlin.math.abs

data class VideoSnapshotData(
    @SerializedName("pvdata")
    val pvdata: String? = null,
    @SerializedName("img_x_len")
    val imgXLen: Int = 0,
    @SerializedName("img_y_len")
    val imgYLen: Int = 0,
    @SerializedName("img_x_size")
    val imgXSize: Int = 0,
    @SerializedName("img_y_size")
    val imgYSize: Int = 0,
    @SerializedName("image")
    val images: List<String> = emptyList(),
    @SerializedName("index")
    val index: List<Long> = emptyList()
) {

    data class Frame(
        val imageUrl: String,
        val imageIndex: Int,
        val offsetX: Int,
        val offsetY: Int,
        val width: Int,
        val height: Int,
        val cacheKey: String
    )

    fun quantizeToFrameMs(targetPositionMs: Long): Long? {
        if (index.size <= 1 || imgXLen <= 0 || imgYLen <= 0) return null
        val timeline = index.drop(1)
        if (timeline.isEmpty()) return null
        val targetSecond = targetPositionMs.coerceAtLeast(0L) / 1000L
        val frameIndex = findNearestFrameIndex(timeline, targetSecond)
        return timeline[frameIndex] * 1000L
    }

    fun resolveFrame(targetPositionMs: Long): Frame? {
        if (images.isEmpty() || index.size <= 1 || imgXLen <= 0 || imgYLen <= 0 || imgXSize <= 0 || imgYSize <= 0) {
            return null
        }

        val timeline = index.drop(1)
        if (timeline.isEmpty()) {
            return null
        }

        val targetSecond = (targetPositionMs.coerceAtLeast(0L) / 1000L)
        val frameIndex = findNearestFrameIndex(timeline, targetSecond)
        val tilesPerImage = imgXLen * imgYLen
        if (tilesPerImage <= 0) {
            return null
        }

        val imageIndex = frameIndex / tilesPerImage
        val sheetUrl = images.getOrNull(imageIndex)?.normalizeUrl() ?: return null
        val tileIndexInImage = frameIndex % tilesPerImage
        val column = tileIndexInImage % imgXLen
        val row = tileIndexInImage / imgXLen
        val offsetX = column * imgXSize
        val offsetY = row * imgYSize

        return Frame(
            imageUrl = sheetUrl,
            imageIndex = imageIndex,
            offsetX = offsetX,
            offsetY = offsetY,
            width = imgXSize,
            height = imgYSize,
            cacheKey = "$sheetUrl#$frameIndex"
        )
    }

    private fun findNearestFrameIndex(timeline: List<Long>, targetSecond: Long): Int {
        val directIndex = timeline.binarySearch(targetSecond)
        if (directIndex >= 0) {
            return directIndex
        }

        val insertionPoint = -directIndex - 1
        return when {
            insertionPoint <= 0 -> 0
            insertionPoint >= timeline.size -> timeline.lastIndex
            else -> {
                val previousIndex = insertionPoint - 1
                val nextIndex = insertionPoint
                val previousDelta = abs(timeline[previousIndex] - targetSecond)
                val nextDelta = abs(timeline[nextIndex] - targetSecond)
                if (previousDelta <= nextDelta) previousIndex else nextIndex
            }
        }
    }

    private fun String.normalizeUrl(): String {
        return when {
            startsWith("https://") -> this
            startsWith("http://") -> "https://${removePrefix("http://")}"
            startsWith("//") -> "https:$this"
            else -> this
        }
    }
}
