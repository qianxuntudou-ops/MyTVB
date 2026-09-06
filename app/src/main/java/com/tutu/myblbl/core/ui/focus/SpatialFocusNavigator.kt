package com.tutu.myblbl.core.ui.focus

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

object SpatialFocusNavigator {

    fun requestBestCandidate(
        anchorView: View?,
        candidates: List<View>,
        direction: Int,
        fallback: (() -> Boolean)? = null
    ): Boolean {
        val focusableCandidates = candidates.filter(::isEligibleCandidate)
        if (focusableCandidates.isEmpty()) {
            return fallback?.invoke() == true
        }
        val anchorRect = anchorView?.visibleRectOnScreen()
        if (anchorRect == null) {
            return fallback?.invoke() == true || focusableCandidates.firstOrNull()?.requestFocus() == true
        }

        val target = findBestCandidate(anchorRect, focusableCandidates, direction)
        return when {
            target?.requestFocus() == true -> true
            fallback?.invoke() == true -> true
            else -> false
        }
    }

    fun requestBestDescendant(
        anchorView: View?,
        root: View?,
        direction: Int,
        fallback: (() -> Boolean)? = null
    ): Boolean {
        val descendants = mutableListOf<View>()
        collectFocusableDescendants(root, descendants)
        return requestBestCandidate(
            anchorView = anchorView,
            candidates = descendants,
            direction = direction,
            fallback = fallback
        )
    }

    private fun findBestCandidate(
        anchorRect: Rect,
        candidates: List<View>,
        direction: Int
    ): View? {
        return candidates
            .mapNotNull { candidate ->
                val candidateRect = candidate.visibleRectOnScreen() ?: return@mapNotNull null
                if (!isCandidateInDirection(anchorRect, candidateRect, direction)) {
                    return@mapNotNull null
                }
                Candidate(
                    view = candidate,
                    score = CandidateScore(
                        inBeam = hasBeamOverlap(anchorRect, candidateRect, direction),
                        majorAxisDistance = majorAxisDistance(anchorRect, candidateRect, direction),
                        minorAxisDistance = minorAxisDistance(anchorRect, candidateRect, direction)
                    )
                )
            }
            .minWithOrNull(compareBy<Candidate>(
                { !it.score.inBeam },
                { it.score.weightedDistance },
                { it.score.majorAxisDistance },
                { it.score.minorAxisDistance }
            ))
            ?.view
    }

    private fun isEligibleCandidate(view: View): Boolean {
        return view.visibility == View.VISIBLE && view.isFocusable && view.isShown
    }

    private fun collectFocusableDescendants(view: View?, out: MutableList<View>) {
        when (view) {
            null -> return
            is ViewGroup -> {
                if (isEligibleCandidate(view) && !isScrollContainer(view)) {
                    out += view
                }
                for (index in 0 until view.childCount) {
                    collectFocusableDescendants(view.getChildAt(index), out)
                }
            }
            else -> {
                if (isEligibleCandidate(view)) {
                    out += view
                }
            }
        }
    }

    /**
     * 滚动容器的 focusable 是框架默认值，不代表它是合法的焦点目标：
     * 焦点若落在容器自身上没有任何视觉反馈（表现为焦点"消失"），
     * 因此空间搜索只收集其子项，跳过容器本身。
     */
    private fun isScrollContainer(view: View): Boolean {
        return view is RecyclerView ||
            view is ViewPager2 ||
            view is AbsListView ||
            view is ScrollView ||
            view is HorizontalScrollView ||
            view is NestedScrollView
    }

    private fun View.visibleRectOnScreen(): Rect? {
        val rect = Rect()
        return if (getGlobalVisibleRect(rect) && !rect.isEmpty) rect else null
    }

    private fun isCandidateInDirection(source: Rect, candidate: Rect, direction: Int): Boolean {
        return when (direction) {
            View.FOCUS_LEFT -> candidate.centerX() < source.centerX()
            View.FOCUS_RIGHT -> candidate.centerX() > source.centerX()
            View.FOCUS_UP -> candidate.centerY() < source.centerY()
            View.FOCUS_DOWN -> candidate.centerY() > source.centerY()
            else -> false
        }
    }

    private fun hasBeamOverlap(source: Rect, candidate: Rect, direction: Int): Boolean {
        return when (direction) {
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> overlaps(source.top, source.bottom, candidate.top, candidate.bottom)
            View.FOCUS_UP, View.FOCUS_DOWN -> overlaps(source.left, source.right, candidate.left, candidate.right)
            else -> false
        }
    }

    private fun overlaps(start1: Int, end1: Int, start2: Int, end2: Int): Boolean {
        return maxOf(start1, start2) < minOf(end1, end2)
    }

    private fun majorAxisDistance(source: Rect, candidate: Rect, direction: Int): Int {
        val edgeDistance = when (direction) {
            View.FOCUS_LEFT -> source.left - candidate.right
            View.FOCUS_RIGHT -> candidate.left - source.right
            View.FOCUS_UP -> source.top - candidate.bottom
            View.FOCUS_DOWN -> candidate.top - source.bottom
            else -> Int.MAX_VALUE
        }
        if (edgeDistance > 0) {
            return edgeDistance
        }
        return when (direction) {
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> kotlin.math.abs(source.centerX() - candidate.centerX())
            View.FOCUS_UP, View.FOCUS_DOWN -> kotlin.math.abs(source.centerY() - candidate.centerY())
            else -> Int.MAX_VALUE
        }
    }

    private fun minorAxisDistance(source: Rect, candidate: Rect, direction: Int): Int {
        return when (direction) {
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> kotlin.math.abs(source.centerY() - candidate.centerY())
            View.FOCUS_UP, View.FOCUS_DOWN -> kotlin.math.abs(source.centerX() - candidate.centerX())
            else -> Int.MAX_VALUE
        }
    }

    private data class Candidate(
        val view: View,
        val score: CandidateScore
    )

    private data class CandidateScore(
        val inBeam: Boolean,
        val majorAxisDistance: Int,
        val minorAxisDistance: Int
    ) {
        val weightedDistance: Long
            get() = 13L * majorAxisDistance * majorAxisDistance + minorAxisDistance.toLong() * minorAxisDistance
    }
}
