package dev.surge.worktick

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import java.text.NumberFormat
import java.util.Locale

class SmoothActivity : Activity() {

    private lateinit var moneyView: MoneyView
    private lateinit var statusPill: TextView
    private lateinit var subline: TextView
    private var schedule: Schedule? = null
    private var lastSecondLabeled = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val tfBold = ResourcesCompat.getFont(this, R.font.jetbrains_mono_bold)
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val tfMedium = ResourcesCompat.getFont(this, R.font.jetbrains_mono_medium)
            ?: Typeface.SANS_SERIF

        statusPill = TextView(this).apply {
            text = "OFF"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = tfBold
            letterSpacing = 0.18f
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = makePillBg(Color.parseColor("#2A2A30"))
        }
        val pillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(statusPill)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                topMargin = dp(80)
            }
        }

        moneyView = MoneyView(this).apply {
            typeface = tfBold
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        subline = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#9A9AA5"))
            typeface = tfMedium
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(60)
            }
        }

        root.addView(moneyView)
        root.addView(pillContainer)
        root.addView(subline)
        setContentView(root)

        root.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        schedule = ScheduleStore.read(this)
        moneyView.schedule = schedule
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            moneyView.invalidate()
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastSecondLabeled >= 1000) {
                lastSecondLabeled = nowMs
                updateLabels()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updateLabels() {
        val s = schedule ?: return
        val now = System.currentTimeMillis() / 1000
        val computed = Math.allTime(s.blocks, now)
        val isActive = computed.activeStart != null

        if (isActive) {
            statusPill.text = "ON THE CLOCK"
            statusPill.background = makePillBg(Color.parseColor("#16A34A"))
        } else {
            statusPill.text = "OFF"
            statusPill.background = makePillBg(Color.parseColor("#2A2A30"))
        }

        val totalHours = computed.totalSeconds(now) / 3600.0
        subline.text = "%.2fh worked  ·  %s/hr".format(totalHours, formatMoney(s.hourlyRate))
    }

    private fun makePillBg(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 1000f
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun formatMoney(amount: Double): String {
        val fmt = NumberFormat.getCurrencyInstance(Locale.US)
        fmt.maximumFractionDigits = 2
        fmt.minimumFractionDigits = 2
        return fmt.format(amount)
    }
}

class MoneyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var schedule: Schedule? = null
    var typeface: Typeface? = null
        set(value) {
            field = value
            if (value != null) {
                paint.typeface = value
                glowPaint.typeface = value
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        try {
            fontFeatureSettings = "tnum, ss01"
        } catch (_: Throwable) { }
        setShadowLayer(28f, 0f, 0f, Color.argb(120, 255, 255, 255))
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        maskFilter = android.graphics.BlurMaskFilter(36f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val textBounds = android.graphics.Rect()
    private val moneyFmt = NumberFormat.getCurrencyInstance(Locale.US).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
        roundingMode = java.math.RoundingMode.FLOOR
    }
    private var lastShaderHeight = 0
    private var lastShaderTextSize = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = schedule ?: return
        val nowMs = System.currentTimeMillis()
        val computed = Math.allTime(s.blocks, nowMs / 1000)
        val dollars = computed.totalDollarsMs(nowMs, s.hourlyRate)
        val text = moneyFmt.format(dollars)

        val targetWidth = width * 0.85f
        var size = height * 0.42f
        paint.textSize = size
        paint.getTextBounds(text, 0, text.length, textBounds)
        if (textBounds.width() > targetWidth) {
            size *= targetWidth / textBounds.width()
            paint.textSize = size
        }
        glowPaint.textSize = size

        paint.getTextBounds(text, 0, text.length, textBounds)
        val cx = width / 2f
        val cy = height / 2f - textBounds.exactCenterY()

        if (height != lastShaderHeight || size != lastShaderTextSize) {
            val ascent = paint.fontMetrics.ascent
            val descent = paint.fontMetrics.descent
            val top = cy + ascent
            val bottom = cy + descent
            paint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#E6E6F0"),
                    Color.parseColor("#A8A8B8")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            lastShaderHeight = height
            lastShaderTextSize = size
        }

        canvas.drawText(text, cx, cy, glowPaint)
        canvas.drawText(text, cx, cy, paint)
    }
}
