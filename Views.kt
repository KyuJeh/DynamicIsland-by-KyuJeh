package com.gabs.dynamicisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.max
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Jendela transparan berisi pil hitam. Pil dianimasikan DI DALAM jendela supaya mulus (tanpa relayout jendela tiap frame). */
class IslandView(c: Context) : FrameLayout(c) {
    private var curW = 0f
    private var curH = 0f
    private var tgtW = 0
    private var tgtH = 0
    private var boxW = 0
    private var boxH = 0
    private var content: View? = null
    private var anim: ValueAnimator? = null
    private var touching = false
    private val radiusMax = c.dp(44).toFloat()

    var onTap: (() -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null

    private inner class Pill(c: Context) : FrameLayout(c) {
        init {
            setBackgroundColor(Color.BLACK)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, min(view.height / 2f, radiusMax))
                }
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                val lp = ch.layoutParams
                ch.measure(
                    MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                )
            }
            setMeasuredDimension(curW.roundToInt(), curH.roundToInt())
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                val cl = (r - l - ch.measuredWidth) / 2
                ch.layout(cl, 0, cl + ch.measuredWidth, ch.measuredHeight)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            invalidateOutline()
        }
    }

    private val pill = Pill(c)

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (vy < -500f && abs(vy) > abs(vx)) {
                onSwipeUp?.invoke()
                return true
            }
            return false
        }
    })

    init {
        addView(pill, LayoutParams(0, 0))
    }

    /** Pegas teredam (damping 0.72) -> mulus, pantulan kecil ~4% seperti iOS. */
    private fun spring(f: Float, durMs: Long): Float {
        val t = f * durMs / 1000f
        val zw = 12.96f
        val wd = 12.49f
        return 1f - exp(-zw * t) * (cos(wd * t) + (zw / wd) * sin(wd * t))
    }

    fun morph(w: Int, h: Int, newContent: View?, duration: Long = 550L) {
        anim?.cancel()
        content?.let { old ->
            old.animate().alpha(0f).setDuration(90).withEndAction { pill.removeView(old) }.start()
        }
        content = newContent
        tgtW = w
        tgtH = h
        if (newContent != null) {
            newContent.alpha = 0f
            pill.addView(newContent, LayoutParams(w, h))
            newContent.animate().alpha(1f).setStartDelay(duration / 5).setDuration(duration / 3).start()
        }
        if (curW <= 0f || curH <= 0f) {
            curW = w.toFloat()
            curH = h.toFloat()
        }
        val sw = curW
        val sh = curH
        // jendela dibuat cukup besar selama animasi (termasuk ruang pantulan)
        boxW = ceil(max(sw, w.toFloat()) * 1.08f).toInt() + 2
        boxH = ceil(max(sh, h.toFloat()) * 1.08f).toInt() + 2
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedFraction
                val e = if (f >= 1f) 1f else spring(f, duration)
                curW = sw + (w - sw) * e
                curH = sh + (h - sh) * e
                pill.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        boxW = w
                        boxH = h
                        requestLayout()
                    }
                }
            })
            start()
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        pill.measure(
            MeasureSpec.makeMeasureSpec(curW.roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(curH.roundToInt(), MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(boxW, boxH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pl = (r - l - pill.measuredWidth) / 2
        pill.layout(pl, 0, pl + pill.measuredWidth, pill.measuredHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touching = event.x >= pill.left && event.x <= pill.right && event.y <= pill.bottom
        }
        if (!touching) return false
        return gd.onTouchEvent(event)
    }
}

/** Equalizer 4 batang yang menari saat musik diputar. */
class EqView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val ks = intArrayOf(1, 2, 1, 3)

    var color: Int = Color.parseColor("#FF2D55")
        set(v) {
            field = v
            p.color = v
            invalidate()
        }

    var active: Boolean = false
        set(v) {
            field = v
            if (v && isAttachedToWindow) anim.start() else if (!v) anim.cancel()
            invalidate()
        }

    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        p.color = color
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val bw = width / (n * 2f - 1f)
        for (i in 0 until n) {
            val s = if (active) 0.3f + 0.7f * abs(sin(2.0 * PI * (ks[i] * t + i * 0.25f))).toFloat() else 0.25f
            val bh = height * s
            val l = i * 2 * bw
            canvas.drawRoundRect(l, (height - bh) / 2f, l + bw, (height + bh) / 2f, bw / 2f, bw / 2f, p)
        }
    }
}

/** Ikon tombol media (prev / play / pause / next) digambar manual. */
class GlyphView(c: Context, var type: Int) : View(c) {
    companion object {
        const val PREV = 0
        const val PLAY = 1
        const val PAUSE = 2
        const val NEXT = 3
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun setGlyph(t: Int) {
        type = t
        invalidate()
    }

    private fun tri(cv: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        cv.drawPath(path, p)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.04f
        when (type) {
            PLAY -> tri(canvas, w * 0.32f, h * 0.2f, w * 0.32f, h * 0.8f, w * 0.8f, h * 0.5f)
            PAUSE -> {
                canvas.drawRoundRect(w * 0.27f, h * 0.2f, w * 0.43f, h * 0.8f, r, r, p)
                canvas.drawRoundRect(w * 0.57f, h * 0.2f, w * 0.73f, h * 0.8f, r, r, p)
            }
            NEXT -> {
                tri(canvas, w * 0.2f, h * 0.28f, w * 0.2f, h * 0.72f, w * 0.62f, h * 0.5f)
                canvas.drawRoundRect(w * 0.66f, h * 0.28f, w * 0.78f, h * 0.72f, r, r, p)
            }
            PREV -> {
                tri(canvas, w * 0.8f, h * 0.28f, w * 0.8f, h * 0.72f, w * 0.38f, h * 0.5f)
                canvas.drawRoundRect(w * 0.22f, h * 0.28f, w * 0.34f, h * 0.72f, r, r, p)
            }
        }
    }
}

class ProgressLine(c: Context) : View(c) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    var fraction: Float = 0f
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
        if (fraction > 0f) {
            canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), r, r, fill)
        }
    }
}

class BatteryView(c: Context) : View(c) {
    private val green = Color.parseColor("#30D158")
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99FFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green }

    var level: Int = 0
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val sw = resources.displayMetrics.density * 1.6f
        line.strokeWidth = sw
        val h = height.toFloat()
        val bw = width * 0.92f
        val rr = h * 0.28f
        canvas.drawRoundRect(RectF(sw / 2, sw / 2, bw - sw / 2, h - sw / 2), rr, rr, line)
        val inset = sw * 1.8f
        val full = bw - inset * 2
        canvas.drawRoundRect(
            RectF(inset, inset, inset + full * level.coerceIn(0, 100) / 100f, h - inset),
            rr * 0.5f, rr * 0.5f, fill
        )
        canvas.drawRoundRect(RectF(bw + sw * 0.3f, h * 0.35f, width.toFloat(), h * 0.65f), sw, sw, line.apply { style = Paint.Style.FILL })
        line.style = Paint.Style.STROKE
    }
}


/** Panah belok kanan sederhana (dipakai untuk tes & cadangan bila Maps tidak mengirim ikon). */
class ArrowDrawable(private val color: Int = Color.WHITE) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val l = bounds.left.toFloat()
        val t = bounds.top.toFloat()
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = w * 0.13f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val path = Path()
        path.moveTo(l + w * 0.3f, t + h * 0.86f)
        path.lineTo(l + w * 0.3f, t + h * 0.5f)
        path.quadTo(l + w * 0.3f, t + h * 0.3f, l + w * 0.5f, t + h * 0.3f)
        path.lineTo(l + w * 0.6f, t + h * 0.3f)
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        val a = Path()
        a.moveTo(l + w * 0.6f, t + h * 0.12f)
        a.lineTo(l + w * 0.6f, t + h * 0.48f)
        a.lineTo(l + w * 0.9f, t + h * 0.3f)
        a.close()
        canvas.drawPath(a, p)
    }

    override fun setAlpha(alpha: Int) {
        p.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        p.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
