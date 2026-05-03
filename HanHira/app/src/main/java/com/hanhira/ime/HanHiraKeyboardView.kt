package com.hanhira.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.content.ContextCompat

@Suppress("DEPRECATION")
class HanHiraKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : KeyboardView(context, attrs, defStyleAttr) {

    interface PreviewCallback {
        fun onPreview(label: String?, x: Int, y: Int, width: Int, height: Int)
        fun onPreviewHidden()
    }

    var previewCallback: PreviewCallback? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 18f * resources.displayMetrics.scaledDensity
    }
    private var pressedKey: Keyboard.Key? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val kb = keyboard ?: return
        val baselineAdjust = (textPaint.descent() + textPaint.ascent()) / 2f
        for (key in kb.keys) {
            val label = key.label?.toString().orEmpty()
            if (label.isEmpty()) continue
            val cx = key.x + key.width / 2f
            val cy = key.y + key.height / 2f - baselineAdjust
            textPaint.textSize = when {
                label.length >= 5 -> 12f * resources.displayMetrics.scaledDensity
                label.length >= 3 -> 14f * resources.displayMetrics.scaledDensity
                else -> 18f * resources.displayMetrics.scaledDensity
            }
            canvas.drawText(label, cx, cy, textPaint)
        }
    }

    override fun onTouchEvent(me: MotionEvent): Boolean {
        when (me.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val key = findKey(me.x.toInt(), me.y.toInt())
                if (key != pressedKey) {
                    pressedKey = key
                    key?.let { showPreviewForKey(it) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                previewCallback?.onPreviewHidden()
            }
        }
        return super.onTouchEvent(me)
    }

    private fun findKey(x: Int, y: Int): Keyboard.Key? {
        return keyboard?.keys?.firstOrNull { key ->
            x >= key.x && x <= key.x + key.width && y >= key.y && y <= key.y + key.height
        }
    }

    private fun showPreviewForKey(key: Keyboard.Key) {
        val label = key.label?.toString()
        if (label.isNullOrBlank() || key.codes.firstOrNull() in setOf(-1, -2, -5, -84)) {
            previewCallback?.onPreviewHidden()
            return
        }
        previewCallback?.onPreview(label, key.x, key.y, key.width, key.height)
    }

    fun setShiftVisual(on: Boolean) {
        isShifted = on
        invalidateAllKeys()
    }
}
