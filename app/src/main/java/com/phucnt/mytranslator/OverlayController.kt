package com.phucnt.mytranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A draggable floating subtitle window drawn over other apps (requires the
 * "display over other apps" permission). Shows the latest translation so the
 * user can read live subtitles while using YouTube, a browser, etc.
 */
class OverlayController(
    private val context: Context,
    private val onClose: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var subtitle: TextView? = null
    private var fontSp = 22f

    private var lastFinal = ""

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC0E1116"))
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        bar.addView(barButton("A-") { setFont(fontSp - 2) })
        bar.addView(barButton("A+") { setFont(fontSp + 2) })
        bar.addView(barButton("✕") { onClose() })

        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = fontSp
            text = context.getString(R.string.waiting)
        }
        subtitle = text

        container.addView(bar)
        container.addView(text)
        root = container

        val params = WindowManager.LayoutParams(
            (resWidth() * 0.92f).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)
        }

        // Drag to reposition.
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        bar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        wm.addView(container, params)
    }

    /** [provisional] is the live (not-yet-final) tail appended after the last final. */
    fun update(finalTail: String, provisional: String) {
        if (finalTail.isNotBlank()) lastFinal = finalTail
        val combined = (lastFinal + " " + provisional).trim()
        subtitle?.post { subtitle?.text = if (combined.isBlank()) context.getString(R.string.waiting) else combined }
    }

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        subtitle = null
    }

    private fun setFont(sp: Float) {
        fontSp = sp.coerceIn(12f, 48f)
        subtitle?.textSize = fontSp
    }

    private fun barButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#33FFFFFF"))
        minWidth = dp(40)
        minimumWidth = dp(40)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
        lp.marginStart = dp(6)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun resWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).roundToInt()
}
