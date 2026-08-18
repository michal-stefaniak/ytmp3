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
            // maxMsPerPx() is 0 before setPeaks runs (trackDurationMs == 0) and can fall below
            // minMsPerPx() on tracks shorter than the view's pixel width — coerceIn(lo, hi) throws
            // if hi < lo, so hi is floored at lo to keep the range valid in both cases.
            val hi = maxMsPerPx().coerceAtLeast(minMsPerPx())
            msPerPx = (msPerPx / detector.scaleFactor).coerceIn(minMsPerPx(), hi)
            scrollOffsetMs = scrollOffsetMs.coerceIn(0f, max(0f, trackDurationMs - width * msPerPx))
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
            if (draggingHandle != null) return
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

    fun currentRegions(): List<RegionMarker> = regions.toList()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // If setPeaks ran before the first layout pass, msPerPx was derived from width=0 and needs
        // re-deriving now that the real width is known.
        if (trackDurationMs > 0) {
            msPerPx = msPerPx.coerceIn(minMsPerPx(), maxMsPerPx().coerceAtLeast(minMsPerPx()))
            scrollOffsetMs = scrollOffsetMs.coerceIn(0f, max(0f, trackDurationMs - w * msPerPx))
        }
    }

    private fun minMsPerPx() = max(1f, trackDurationMs / max(1f, peaks.size.toFloat()) / 4f) // don't zoom in past ~1/4 of a peak bucket per pixel -- there's no more data to show
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
                // No early return here: letting every MOVE reach gestureDetector below is what
                // lets its internal long-press timer see the movement and cancel itself once slop
                // is crossed. An early return here starves it of MOVE events, so a stale long-press
                // fires mid-drag and creates a phantom region on release (see ACTION_UP) — the
                // onScroll guard above is what keeps this safe from also triggering a pan.
                draggingHandle?.let { (region, isStart) ->
                    val newMs = xToMs(event.x)
                    val idx = regions.indexOfFirst { it.id == region.id }
                    if (idx == -1) return@let
                    val current = regions[idx]
                    val ordered = regions.sortedBy { it.startMs }
                    val orderedIndex = ordered.indexOfFirst { it.id == current.id }
                    val previousEnd = ordered.getOrNull(orderedIndex - 1)?.endMs ?: 0L
                    val nextStart = ordered.getOrNull(orderedIndex + 1)?.startMs ?: trackDurationMs
                    // clampDraggedStart/End stop the dragged handle at its sibling handle instead of
                    // letting RegionMarker.clamp see a start >= end and silently relocate the whole
                    // region to a new short window elsewhere on the track.
                    val (clampedStart, clampedEnd) = if (isStart) {
                        RegionMarker.clampDraggedStart(newMs, current.endMs, trackDurationMs, previousEnd)
                    } else {
                        RegionMarker.clampDraggedEnd(newMs, current.startMs, trackDurationMs, nextStart)
                    }
                    regions[idx] = current.copy(startMs = clampedStart, endMs = clampedEnd)
                    invalidate()
                }
                pendingRegionStartMs?.let {
                    pendingRegionEndMs = xToMs(event.x)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingHandle != null) {
                    draggingHandle = null
                    onRegionsChanged?.invoke(regions.toList())
                }
                pendingRegionStartMs?.let { startMs ->
                    val endMs = pendingRegionEndMs ?: startMs
                    val (s, e) = RegionMarker.clamp(min(startMs, endMs), max(startMs, endMs), trackDurationMs)
                    // A region may meet another region at a handle, but may not overlap it.  This
                    // keeps the editor's temporal ordering stable and makes neighbouring drag
                    // bounds meaningful.
                    if (regions.none { RegionMarker.overlaps(s, e, it.startMs, it.endMs) }) {
                        regions.add(RegionMarker(startMs = s, endMs = e))
                        regions.sortBy { it.startMs }
                        onRegionsChanged?.invoke(regions.toList())
                    }
                }
                pendingRegionStartMs = null
                pendingRegionEndMs = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                // CANCEL means the gesture was aborted (e.g. a parent view intercepted it) — discard
                // in-progress state rather than committing a region the user never actually released.
                draggingHandle = null
                pendingRegionStartMs = null
                pendingRegionEndMs = null
                invalidate()
            }
        }

        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun findHandleNear(touchMs: Long): Pair<RegionMarker, Boolean>? {
        val toleranceMs = (24 * resources.displayMetrics.density * msPerPx).toLong().coerceAtLeast(1)
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
