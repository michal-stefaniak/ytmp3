package com.ytmp3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onRegionsChanged: ((List<RegionMarker>) -> Unit)? = null
    var onRegionTapped: ((RegionMarker) -> Unit)? = null

    private var peaks: List<PeakMath.PeakBucket> = emptyList()
    private var trackDurationMs: Long = 0
    private var regions: MutableList<RegionMarker> = mutableListOf()

    // msPerPx * viewWidth = visible window; scrollOffsetMs = left edge of the visible window
    private var msPerPx: Float = 1f
    private var scrollOffsetMs: Float = 0f
    private var draggingHandle: Pair<RegionMarker, Boolean>? = null // region, isStartHandle
    private var pendingRegionStartMs: Long? = null
    private var pendingRegionEndMs: Long? = null

    private val wavePaint = Paint().apply { color = Color.parseColor("#CC0000"); strokeWidth = 2f }
    private val regionPaint = Paint().apply { color = Color.parseColor("#55CC0000") }
    private val handlePaint = Paint().apply { color = Color.parseColor("#FFFFFF"); strokeWidth = 6f }
    private val bgPaint = Paint().apply { color = Color.parseColor("#111111") }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            msPerPx = (msPerPx / detector.scaleFactor).coerceIn(minMsPerPx(), maxMsPerPx())
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (draggingHandle != null || pendingRegionStartMs != null) return false
            scrollOffsetMs = (scrollOffsetMs + dx * msPerPx).coerceIn(0f, max(0f, trackDurationMs - width * msPerPx))
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val tapMs = xToMs(e.x)
            regions.firstOrNull { tapMs in it.startMs..it.endMs }?.let { onRegionTapped?.invoke(it) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            pendingRegionStartMs = xToMs(e.x)
        }
    })

    fun setPeaks(peaks: List<PeakMath.PeakBucket>, trackDurationMs: Long) {
        this.peaks = peaks
        this.trackDurationMs = trackDurationMs
        msPerPx = trackDurationMs / max(1f, width.toFloat())
        invalidate()
    }

    fun setRegions(regions: List<RegionMarker>) {
        this.regions = regions.toMutableList()
        invalidate()
    }

    private fun minMsPerPx() = 1f // 1ms/px = max zoom in
    private fun maxMsPerPx() = trackDurationMs / max(1f, width.toFloat())

    private fun xToMs(x: Float): Long = (scrollOffsetMs + x * msPerPx).toLong().coerceIn(0, trackDurationMs)
    private fun msToX(ms: Long): Float = (ms - scrollOffsetMs) / msPerPx

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchMs = xToMs(event.x)
                draggingHandle = findHandleNear(touchMs)
            }
            MotionEvent.ACTION_MOVE -> {
                draggingHandle?.let { (region, isStart) ->
                    val newMs = xToMs(event.x)
                    val idx = regions.indexOfFirst { it.id == region.id }
                    if (idx == -1) return@let
                    val current = regions[idx]
                    val (clampedStart, clampedEnd) = if (isStart) {
                        RegionMarker.clamp(newMs, current.endMs, trackDurationMs)
                    } else {
                        RegionMarker.clamp(current.startMs, newMs, trackDurationMs)
                    }
                    regions[idx] = current.copy(startMs = clampedStart, endMs = clampedEnd)
                    invalidate()
                    return true
                }
                pendingRegionStartMs?.let {
                    pendingRegionEndMs = xToMs(event.x)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle != null) {
                    draggingHandle = null
                    onRegionsChanged?.invoke(regions.toList())
                }
                pendingRegionStartMs?.let { startMs ->
                    val endMs = pendingRegionEndMs ?: startMs
                    val (s, e) = RegionMarker.clamp(min(startMs, endMs), max(startMs, endMs), trackDurationMs)
                    regions.add(RegionMarker(startMs = s, endMs = e))
                    onRegionsChanged?.invoke(regions.toList())
                    pendingRegionStartMs = null
                    pendingRegionEndMs = null
                    invalidate()
                }
            }
        }

        return gestureDetector.onTouchEvent(event) || true
    }

    private fun findHandleNear(touchMs: Long, toleranceMs: Long = 200): Pair<RegionMarker, Boolean>? {
        for (region in regions) {
            if (kotlin.math.abs(region.startMs - touchMs) <= toleranceMs) return region to true
            if (kotlin.math.abs(region.endMs - touchMs) <= toleranceMs) return region to false
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (peaks.isEmpty()) return

        val msPerBucket = trackDurationMs.toFloat() / peaks.size
        val midY = height / 2f
        val scaleY = height / 2f / Short.MAX_VALUE.toFloat()

        val firstVisibleBucket = (scrollOffsetMs / msPerBucket).toInt().coerceIn(0, peaks.size - 1)
        val lastVisibleBucket = ((scrollOffsetMs + width * msPerPx) / msPerBucket).toInt().coerceIn(0, peaks.size - 1)

        for (i in firstVisibleBucket..lastVisibleBucket) {
            val bucket = peaks[i]
            val x = msToX((i * msPerBucket).toLong())
            canvas.drawLine(x, midY - bucket.max * scaleY, x, midY - bucket.min * scaleY, wavePaint)
        }

        for (region in regions) {
            val left = msToX(region.startMs)
            val right = msToX(region.endMs)
            canvas.drawRect(left, 0f, right, height.toFloat(), regionPaint)
            canvas.drawLine(left, 0f, left, height.toFloat(), handlePaint)
            canvas.drawLine(right, 0f, right, height.toFloat(), handlePaint)
        }

        val pendingStart = pendingRegionStartMs
        if (pendingStart != null) {
            val pendingEnd = pendingRegionEndMs ?: pendingStart
            val left = msToX(min(pendingStart, pendingEnd))
            val right = msToX(max(pendingStart, pendingEnd))
            canvas.drawRect(left, 0f, right, height.toFloat(), regionPaint)
        }
    }
}
