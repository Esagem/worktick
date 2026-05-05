package dev.surge.worktick

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

        statusPill = TextView(this).apply {
            text = "OFF"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.15f
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
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        subline = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#8A8A8A"))
            typeface = Typeface.SANS_SERIF
            letterSpacing = 0.06f
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        try {
            fontFeatureSettings = "tnum"
        } catch (_: Throwable) { }
    }

    private val textBounds = android.graphics.Rect()
    private val moneyFmt = NumberFormat.getCurrencyInstance(Locale.US).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = schedule ?: return
        val nowSec = System.currentTimeMillis() / 1000
        val computed = Math.allTime(s.blocks, nowSec)
        val dollars = computed.totalDollars(nowSec, s.hourlyRate)
        val text = moneyFmt.format(dollars)

        val targetWidth = width * 0.85f
        var size = height * 0.4f
        paint.textSize = size
        paint.getTextBounds(text, 0, text.length, textBounds)
        if (textBounds.width() > targetWidth) {
            size *= targetWidth / textBounds.width()
            paint.textSize = size
        }

        paint.getTextBounds(text, 0, text.length, textBounds)
        val cx = width / 2f
        val cy = height / 2f - textBounds.exactCenterY()
        canvas.drawText(text, cx, cy, paint)
    }
}
