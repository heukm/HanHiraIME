package com.hanpin.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HanPinIME : InputMethodService(), KeyboardView.OnKeyboardActionListener {
    private enum class Mode { HANGUL, PINYIN }

    private lateinit var candidateEngine: CandidateEngine
    private lateinit var hangulKeyboard: Keyboard
    private lateinit var pinyinKeyboard: Keyboard
    private lateinit var rootView: LinearLayout
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var candidateRow: LinearLayout
    private lateinit var keyboardView: HanPinKeyboardView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val requestToken = AtomicInteger(0)
    private val hangulComposer = HangulComposer(maxCodePoints = 11)
    private val pinyinBuffer = StringBuilder()

    private var mode = Mode.HANGUL
    private var shiftOn = false
    private var lastCandidates: List<String> = emptyList()

    private var deleting = false
    private var longPressActivated = false
    private val deleteRunnable = object : Runnable {
        override fun run() {
            if (!deleting) return
            longPressActivated = true
            if (hasComposingText()) {
                cancelCompositionOnly()
            } else {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
            mainHandler.postDelayed(this, 60L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        candidateEngine = CandidateEngine(ModelRunner(applicationContext))
    }

    override fun onCreateInputView(): View {
        val padding = dp(4)
        candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        candidateScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(candidateRow)
        }
        keyboardView = HanPinKeyboardView(this, null).apply {
            setOnKeyboardActionListener(this@HanPinIME)
            isPreviewEnabled = false
            setBackgroundColor(Color.BLACK)
        }
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(candidateScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(keyboardView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = padding
            })
        }

        hangulKeyboard = Keyboard(this, R.xml.korean_keyboard)
        pinyinKeyboard = Keyboard(this, R.xml.english_keyboard)
        applyKeyboard()
        renderCandidates(emptyList())
        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyKeyboard()
        refreshComposingUi()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        stopDeleteLoop()
        clearState(dropComposingText = true)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        stopDeleteLoop()
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT -> {
                shiftOn = !shiftOn
                Logger.input("Shift toggled: $shiftOn")
                applyKeyboard()
                return
            }
            MODE_SWITCH_CODE -> {
                commitCompositionIfNeeded()
                mode = if (mode == Mode.HANGUL) Mode.PINYIN else Mode.HANGUL
                shiftOn = false
                Logger.input("Mode switched to $mode")
                applyKeyboard()
                return
            }
            Keyboard.KEYCODE_DELETE -> {
                handleDeleteTap()
                resetShiftAfterKey()
                return
            }
            ENTER_CODE -> {
                handleEnter()
                resetShiftAfterKey()
                return
            }
        }

        when {
            primaryCode == 32 -> commitDirect(" ")
            primaryCode in 48..57 -> commitDirect(primaryCode.toChar().toString())
            primaryCode in CHINESE_PUNCT_CODES -> {
                val text = if (shiftOn) SHIFTED_PUNCT[primaryCode].orEmpty() else primaryCode.toChar().toString()
                commitDirect(text)
            }
            mode == Mode.PINYIN -> handlePinyinKey(primaryCode)
            else -> handleHangulKey(primaryCode)
        }
        resetShiftAfterKey()
    }

    override fun onPress(primaryCode: Int) {
        keyboardView.setPressedCode(primaryCode)
        keyboardView.showPreviewFor(primaryCode)
        if (primaryCode == Keyboard.KEYCODE_DELETE) {
            deleting = true
            longPressActivated = false
            mainHandler.postDelayed(deleteRunnable, 500L)
        }
    }

    override fun onRelease(primaryCode: Int) {
        keyboardView.setPressedCode(null)
        keyboardView.hidePreview()
        if (primaryCode == Keyboard.KEYCODE_DELETE) {
            stopDeleteLoop()
        }
    }

    override fun onText(text: CharSequence?) = Unit
    override fun swipeDown() = Unit
    override fun swipeLeft() = Unit
    override fun swipeRight() = Unit
    override fun swipeUp() = Unit

    private fun handleHangulKey(primaryCode: Int) {
        val char = if (shiftOn) SHIFTED_HANGUL[primaryCode] ?: NORMAL_HANGUL[primaryCode]
        else NORMAL_HANGUL[primaryCode]
        if (char == null) {
            commitDirect(primaryCode.toChar().toString())
            return
        }
        if (!hangulComposer.append(char)) {
            return
        }
        Logger.input("Hangul key=$char")
        refreshComposingUi()
    }

    private fun handlePinyinKey(primaryCode: Int) {
        val base = primaryCode.toChar()
        if (shiftOn && base in 'a'..'z') {
            commitDirect(base.uppercaseChar().toString())
            return
        }
        val normalized = base.lowercaseChar()
        if (normalized !in 'a'..'z') {
            commitDirect(base.toString())
            return
        }
        val next = pinyinBuffer.toString() + normalized
        if (PinyinProcessor(emptySet()).normalize(next).length > 48) return
        pinyinBuffer.append(normalized)
        Logger.input("Pinyin key=$normalized")
        refreshComposingUi()
    }

    private fun handleDeleteTap() {
        if (hasComposingText()) {
            if (mode == Mode.HANGUL) {
                hangulComposer.backspace()
            } else if (pinyinBuffer.isNotEmpty()) {
                pinyinBuffer.deleteCharAt(pinyinBuffer.lastIndex)
            }
            Logger.input("Delete composing")
            refreshComposingUi()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleEnter() {
        if (hasComposingText()) {
            commitCompositionIfNeeded()
        }
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val handled = when (action) {
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT -> ic.performEditorAction(action)
            else -> false
        }
        if (!handled) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun commitCandidate(candidate: String) {
        currentInputConnection?.commitText(candidate, 1)
        Logger.commit("Candidate committed: $candidate")
        clearState(dropComposingText = false)
    }

    private fun commitDirect(text: String) {
        if (text.isEmpty()) return
        if (hasComposingText()) {
            commitCompositionIfNeeded()
        }
        currentInputConnection?.commitText(text, 1)
        Logger.commit("Direct committed: $text")
    }

    private fun commitCompositionIfNeeded() {
        val composing = currentComposingText()
        if (composing.isBlank()) return
        currentInputConnection?.commitText(composing, 1)
        Logger.commit("Raw composition committed: $composing")
        clearState(dropComposingText = false)
    }

    private fun cancelCompositionOnly() {
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()
        clearState(dropComposingText = true)
    }

    private fun clearState(dropComposingText: Boolean) {
        hangulComposer.clear()
        pinyinBuffer.setLength(0)
        requestToken.incrementAndGet()
        lastCandidates = emptyList()
        if (dropComposingText) {
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
        }
        renderCandidates(emptyList())
        if (!dropComposingText) {
            currentInputConnection?.finishComposingText()
        }
    }

    private fun refreshComposingUi() {
        val text = currentComposingText()
        if (text.isBlank()) {
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
            requestToken.incrementAndGet()
            renderCandidates(emptyList())
            return
        }
        currentInputConnection?.setComposingText(text, 1)
        Logger.compose("Composing='$text'")
        requestCandidates(text)
    }

    private fun requestCandidates(text: String) {
        val token = requestToken.incrementAndGet()
        val currentMode = mode
        worker.execute {
            val result = when (currentMode) {
                Mode.HANGUL -> candidateEngine.suggestHangul(text)
                Mode.PINYIN -> candidateEngine.suggestPinyin(text)
            }
            if (token != requestToken.get()) return@execute
            mainHandler.post {
                if (token != requestToken.get()) return@post
                lastCandidates = result.candidates
                renderCandidates(result.candidates)
            }
        }
    }

    private fun renderCandidates(candidates: List<String>) {
        candidateRow.removeAllViews()
        candidateRow.setBackgroundColor(Color.BLACK)
        if (candidates.isEmpty()) {
            val empty = TextView(this).apply {
                text = " "
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            candidateRow.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(this)
        candidates.forEach { candidate ->
            val view = inflater.inflate(R.layout.candidate_item, candidateRow, false) as TextView
            view.text = candidate
            view.setTextColor(Color.WHITE)
            view.setBackgroundColor(Color.BLACK)
            view.setOnClickListener { commitCandidate(candidate) }
            candidateRow.addView(view)
        }
        candidateScroll.post { candidateScroll.scrollTo(0, 0) }
    }

    private fun currentComposingText(): String {
        return if (mode == Mode.HANGUL) hangulComposer.composedText() else pinyinBuffer.toString()
    }

    private fun hasComposingText(): Boolean = currentComposingText().isNotBlank()

    private fun applyKeyboard() {
        val keyboard = if (mode == Mode.HANGUL) hangulKeyboard else pinyinKeyboard
        keyboardView.keyboard = keyboard
        updateKeyLabels(keyboard)
        val height = keyboard.keys.maxOfOrNull { it.y + it.height }?.plus(dp(8)) ?: dp(240)
        keyboardView.layoutParams = (keyboardView.layoutParams as LinearLayout.LayoutParams).apply {
            this.height = height
        }
        keyboardView.invalidateAllKeys()
    }

    private fun updateKeyLabels(keyboard: Keyboard) {
        keyboard.keys.forEach { key ->
            val code = key.codes.firstOrNull() ?: return@forEach
            key.label = when {
                code == Keyboard.KEYCODE_SHIFT -> "⇧"
                code == Keyboard.KEYCODE_DELETE -> "⌫"
                code == MODE_SWITCH_CODE -> "한/영"
                code == ENTER_CODE -> "enter"
                code == 32 -> "space"
                code in CHINESE_PUNCT_CODES -> if (shiftOn) SHIFTED_PUNCT[code] else code.toChar().toString()
                mode == Mode.PINYIN && code in LETTER_CODES -> {
                    val letter = code.toChar()
                    if (shiftOn) letter.uppercaseChar().toString() else letter.toString()
                }
                mode == Mode.HANGUL -> {
                    val shifted = SHIFTED_HANGUL[code]
                    if (shiftOn && shifted != null) shifted.toString() else NORMAL_HANGUL[code]?.toString() ?: key.label
                }
                else -> key.label
            }
        }
    }

    private fun resetShiftAfterKey() {
        if (!shiftOn) return
        shiftOn = false
        applyKeyboard()
    }

    private fun stopDeleteLoop() {
        deleting = false
        mainHandler.removeCallbacks(deleteRunnable)
        if (longPressActivated) {
            longPressActivated = false
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        private const val MODE_SWITCH_CODE = -2
        private const val ENTER_CODE = -84
        private val CHINESE_PUNCT_CODES = setOf(65311, 65281, 65292, 12290)
        private val LETTER_CODES = ('a'..'z').map { it.code }.toSet()
        private val SHIFTED_PUNCT = mapOf(
            65311 to "?",
            65281 to "!",
            65292 to ",",
            12290 to ".",
        )
        private val NORMAL_HANGUL = mapOf(
            113 to 'ㅂ', 119 to 'ㅈ', 101 to 'ㄷ', 114 to 'ㄱ', 116 to 'ㅅ',
            121 to 'ㅛ', 117 to 'ㅕ', 105 to 'ㅑ', 111 to 'ㅐ', 112 to 'ㅔ',
            97 to 'ㅁ', 115 to 'ㄴ', 100 to 'ㅇ', 102 to 'ㄹ', 103 to 'ㅎ',
            104 to 'ㅗ', 106 to 'ㅓ', 107 to 'ㅏ', 108 to 'ㅣ',
            122 to 'ㅋ', 120 to 'ㅌ', 99 to 'ㅊ', 118 to 'ㅍ', 98 to 'ㅠ',
            110 to 'ㅜ', 109 to 'ㅡ',
        )
        private val SHIFTED_HANGUL = mapOf(
            113 to 'ㅃ', 119 to 'ㅉ', 101 to 'ㄸ', 114 to 'ㄲ',
            116 to 'ㅆ', 111 to 'ㅒ', 112 to 'ㅖ',
        )
    }
}

private class HanPinKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KeyboardView(context, attrs) {
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4D4D4D") }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D9D9D9") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var pressedCode: Int? = null
    private val previewText = TextView(context).apply {
        setBackgroundColor(Color.parseColor("#4D4D4D"))
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }
    private val previewWindow = PopupWindow(
        previewText,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        false,
    )

    fun setPressedCode(code: Int?) {
        pressedCode = code
        invalidate()
    }

    fun showPreviewFor(primaryCode: Int) {
        val key = keyboard?.keys?.firstOrNull { it.codes.firstOrNull() == primaryCode } ?: return
        val label = key.label?.toString().orEmpty()
        if (label.isBlank()) return

        previewText.text = label
        previewText.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )

        val location = IntArray(2)
        getLocationInWindow(location)
        val popupWidth = previewText.measuredWidth
        val popupHeight = previewText.measuredHeight
        val rawX = location[0] + key.x + (key.width / 2) - (popupWidth / 2)
        val rawY = location[1] + key.y - popupHeight - dp(8)

        val displayWidth = resources.displayMetrics.widthPixels
        val x = rawX.coerceIn(0, (displayWidth - popupWidth).coerceAtLeast(0))
        val y = rawY.coerceAtLeast(0)

        if (previewWindow.isShowing) {
            previewWindow.update(x, y, popupWidth, popupHeight)
        } else {
            previewWindow.showAtLocation(rootView, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun hidePreview() {
        if (previewWindow.isShowing) {
            previewWindow.dismiss()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val keyboard = keyboard ?: return
        val density = resources.displayMetrics.density
        val scaled = resources.displayMetrics.scaledDensity
        for (key in keyboard.keys) {
            val rect = RectF(
                key.x + density,
                key.y + density,
                key.x + key.width - density,
                key.y + key.height - density,
            )
            val paint = if (pressedCode != null && key.codes.firstOrNull() == pressedCode) pressedPaint else normalPaint
            canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), paint)
            val label = key.label?.toString().orEmpty()
            textPaint.textSize = if (label.length > 1) 16f * scaled else 22f * scaled
            textPaint.color = if (pressedCode != null && key.codes.firstOrNull() == pressedCode) Color.BLACK else Color.WHITE
            val y = rect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(label, rect.centerX(), y, textPaint)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }
}
