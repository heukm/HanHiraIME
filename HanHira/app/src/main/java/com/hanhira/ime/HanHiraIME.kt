package com.hanhira.ime

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

@Suppress("DEPRECATION")
class HanHiraIME : InputMethodService(), KeyboardView.OnKeyboardActionListener {
    private lateinit var keyboardView: HanHiraKeyboardView
    private lateinit var candidateContainer: LinearLayout
    private lateinit var previewText: TextView
    private lateinit var koreanKeyboard: Keyboard
    private lateinit var englishKeyboard: Keyboard
    private lateinit var candidateEngine: CandidateEngine

    private val composer = HangulComposer()
    private val internalBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var koreanMode = true
    private var shiftOn = false
    private var deleteRepeating = false
    private var longDeleteClearedComposition = false
    private var selfChanging = false

    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            deleteRepeating = true
            if (!longDeleteClearedComposition && !composer.isEmpty()) {
                Logger.d("delete", "long delete clears composing='${composer.text()}'")
                setComposing("")
                composer.clear()
                longDeleteClearedComposition = true
                refreshCandidates()
            } else {
                handleBackspace(singleFromRepeat = true)
            }
            handler.postDelayed(this, 55L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        candidateEngine = CandidateEngine.get(this)
        candidateEngine.preload()
    }

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.input_view, null)
        keyboardView = root.findViewById(R.id.keyboardView)
        candidateContainer = root.findViewById(R.id.candidateContainer)
        previewText = root.findViewById(R.id.keyPreviewText)
        koreanKeyboard = Keyboard(this, R.xml.korean_keyboard)
        englishKeyboard = Keyboard(this, R.xml.english_keyboard)
        keyboardView.setOnKeyboardActionListener(this)
        keyboardView.previewCallback = object : HanHiraKeyboardView.PreviewCallback {
            override fun onPreview(label: String?, x: Int, y: Int, width: Int, height: Int) {
                if (label.isNullOrBlank()) return
                previewText.text = label
                previewText.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val lp = previewText.layoutParams as android.widget.FrameLayout.LayoutParams
                lp.leftMargin = x + width / 2 - previewText.measuredWidth / 2
                lp.topMargin = (y - previewText.measuredHeight - 6).coerceAtLeast(0)
                previewText.layoutParams = lp
                previewText.visibility = View.VISIBLE
            }

            override fun onPreviewHidden() {
                previewText.visibility = View.GONE
            }
        }
        switchKeyboard(korean = true, clearBuffers = false)
        refreshCandidates()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        clearBuffers("startInput")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::keyboardView.isInitialized) {
            keyboardView.keyboard = if (koreanMode) koreanKeyboard else englishKeyboard
            updateKeyboardLabels()
            refreshCandidates()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        clearBuffers("finishInput")
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // setComposingText() 자체가 selection/composing range 변경 이벤트를 늦게 발생시키는 경우가 있어
        // 이 이벤트로 composer를 지우면 화면에는 밑줄 글자가 남고 IME 내부 상태만 사라진다.
        // 그 상태에서는 백스페이스가 합성 글자를 지우지 못하므로, 활성 composing region이 있으면 무시한다.
        val hasComposingRegion = candidatesStart >= 0 && candidatesEnd >= candidatesStart
        if (!selfChanging && hasComposingRegion && !composer.isEmpty()) {
            Logger.d("selection", "ignore own composing selection candidates=$candidatesStart..$candidatesEnd composing='${composer.text()}'")
            return
        }
        if (!selfChanging && (oldSelStart != newSelStart || oldSelEnd != newSelEnd)) {
            clearBuffers("selectionChanged")
        }
    }

    override fun onPress(primaryCode: Int) {
        Logger.d("key", "press code=$primaryCode")
        if (primaryCode == Keyboard.KEYCODE_DELETE) {
            longDeleteClearedComposition = false
            handler.postDelayed(deleteRepeatRunnable, 500L)
        }
    }

    override fun onRelease(primaryCode: Int) {
        Logger.d("key", "release code=$primaryCode")
        if (primaryCode == Keyboard.KEYCODE_DELETE) {
            handler.removeCallbacks(deleteRepeatRunnable)
            deleteRepeating = false
            longDeleteClearedComposition = false
        }
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        Logger.d("key", "onKey code=$primaryCode shift=$shiftOn korean=$koreanMode composing='${composer.text()}' internal='$internalBuffer'")
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT -> toggleShift()
            Keyboard.KEYCODE_MODE_CHANGE -> switchKeyboard(korean = !koreanMode, clearBuffers = true)
            Keyboard.KEYCODE_DELETE -> if (!deleteRepeating) handleBackspace(singleFromRepeat = false)
            ENTER_CODE -> handleEnter()
            KEY_JP_QUESTION -> handleQuestionOrLongMark()
            KEY_JP_EXCLAMATION -> commitDirect(if (shiftOn) "!" else "！", resetShift = true)
            KEY_JP_COMMA -> commitDirect(if (shiftOn) "," else "、", resetShift = true)
            KEY_JP_PERIOD -> commitDirect(if (shiftOn) "." else "。", resetShift = true)
            SPACE_CODE -> commitDirect(" ", resetShift = true)
            in 48..57 -> commitDirect(primaryCode.toChar().toString(), resetShift = true)
            else -> {
                if (koreanMode) handleKoreanKey(primaryCode) else handleEnglishKey(primaryCode)
            }
        }
    }

    override fun onText(text: CharSequence?) {
        text?.toString()?.takeIf { it.isNotEmpty() }?.let { commitDirect(it, resetShift = true) }
    }
    override fun swipeLeft() = Unit
    override fun swipeRight() = Unit
    override fun swipeDown() = Unit
    override fun swipeUp() = Unit

    private fun handleKoreanKey(code: Int) {
        val jamo = koreanJamoForCode(code, shiftOn)
        if (jamo == null) {
            consumeShiftIfNeeded()
            return
        }
        composer.inputJamo(jamo)
        setComposing(composer.text())
        Logger.d("compose", "jamo=$jamo composing='${composer.text()}'")
        trimInternalToMax()
        refreshCandidates()
        consumeShiftIfNeeded()
    }

    private fun handleEnglishKey(code: Int) {
        if (code in 65..90 || code in 97..122) {
            val ch = code.toChar().let { if (shiftOn) it.uppercaseChar() else it.lowercaseChar() }
            commitDirect(ch.toString(), resetShift = true)
        } else {
            consumeShiftIfNeeded()
        }
    }

    private fun handleQuestionOrLongMark() {
        if (shiftOn) {
            commitDirect("?", resetShift = true)
        } else {
            composer.appendLongMark()
            setComposing(composer.text())
            Logger.d("compose", "long mark composing='${composer.text()}'")
            refreshCandidates()
        }
    }

    private fun handleBackspace(singleFromRepeat: Boolean) {
        val ic = currentInputConnection ?: return
        if (!composer.isEmpty()) {
            val before = composer.text()
            composer.backspace()
            val text = composer.text()
            setComposing(text)
            Logger.d("delete", "composing backspace before='$before' now='$text'")
            refreshCandidates()
            return
        }
        selfEdit {
            ic.deleteSurroundingText(1, 0)
        }
        removeLastCodePoint(internalBuffer)
        Logger.d("delete", "committed backspace repeat=$singleFromRepeat internal='$internalBuffer'")
        refreshCandidates()
    }

    private fun handleEnter() {
        commitComposingAsTyped()
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val didAction = when (action) {
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE -> ic.performEditorAction(action)
            else -> false
        }
        if (!didAction) {
            selfEdit { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
        }
        clearBuffers("enter")
    }

    private fun commitDirect(text: String, resetShift: Boolean) {
        commitComposingAsTyped()
        selfEdit { currentInputConnection?.commitText(text, 1) }
        Logger.d("commit", "direct='$text'")
        if (resetShift) consumeShiftIfNeeded()
        refreshCandidates()
    }

    private fun commitComposingAsTyped() {
        if (composer.isEmpty()) return
        val source = composer.text()
        selfEdit { currentInputConnection?.commitText(source, 1) }
        appendInternalSource(source)
        Logger.d("commit", "typed source='$source' internal='$internalBuffer'")
        composer.clear()
    }

    private fun commitCandidate(candidate: String) {
        val source = composer.text()
        if (source.isEmpty()) return
        selfEdit { currentInputConnection?.commitText(candidate, 1) }
        appendInternalSource(source)
        Logger.d("commit", "candidate='$candidate' source='$source' internal='$internalBuffer'")
        composer.clear()
        refreshCandidates()
    }

    private fun setComposing(text: String) {
        selfEdit {
            val ic = currentInputConnection ?: return@selfEdit
            if (text.isEmpty()) {
                // 일부 Android 15/Samsung 입력창에서 setComposingText("")만으로는
                // 화면의 밑줄 합성 영역이 남는 경우가 있어 빈 composing을 적용한 뒤 종료한다.
                ic.setComposingText("", 1)
                ic.finishComposingText()
            } else {
                ic.setComposingText(text, 1)
            }
        }
    }

    private fun refreshCandidates() {
        if (!::candidateContainer.isInitialized) return
        candidateContainer.removeAllViews()
        val composing = composer.text()
        val candidates = if (composing.isNotEmpty()) candidateEngine.candidates(composing, internalBuffer.toString()) else emptyList()
        Logger.d("candidate", "source='$composing' count=${candidates.size} list=${candidates.take(10)}")
        // 후보가 없으면 “候補 없음” 같은 문구를 넣지 않고 후보창 높이만 유지한 빈 공간으로 둔다.
        for (candidate in candidates) addCandidateView(candidate, enabled = true)
    }

    private fun addCandidateView(text: String, enabled: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.candidate_item, candidateContainer, false) as TextView
        view.text = text
        view.isEnabled = enabled
        if (enabled) view.setOnClickListener { commitCandidate(text) }
        candidateContainer.addView(view)
    }

    private fun switchKeyboard(korean: Boolean, clearBuffers: Boolean) {
        koreanMode = korean
        if (clearBuffers) clearBuffers("modeChange")
        if (::keyboardView.isInitialized) {
            keyboardView.keyboard = if (koreanMode) koreanKeyboard else englishKeyboard
            shiftOn = false
            updateKeyboardLabels()
        }
    }

    private fun toggleShift() {
        shiftOn = !shiftOn
        updateKeyboardLabels()
    }

    private fun consumeShiftIfNeeded() {
        if (shiftOn) {
            shiftOn = false
            updateKeyboardLabels()
        }
    }

    private fun updateKeyboardLabels() {
        val kb = if (koreanMode) koreanKeyboard else englishKeyboard
        for (key in kb.keys) {
            val code = key.codes.firstOrNull() ?: continue
            key.label = when {
                code == Keyboard.KEYCODE_SHIFT -> "⇧"
                code == Keyboard.KEYCODE_MODE_CHANGE -> "한/영"
                code == Keyboard.KEYCODE_DELETE -> "⌫"
                code == ENTER_CODE -> "enter"
                code == SPACE_CODE -> "space"
                code == KEY_JP_QUESTION -> if (shiftOn) "?" else "ー"
                code == KEY_JP_EXCLAMATION -> if (shiftOn) "!" else "！"
                code == KEY_JP_COMMA -> if (shiftOn) "," else "、"
                code == KEY_JP_PERIOD -> if (shiftOn) "." else "。"
                koreanMode -> koreanJamoForCode(code, shiftOn)?.toString() ?: key.label
                code in 65..90 || code in 97..122 -> code.toChar().let { if (shiftOn) it.uppercaseChar() else it.lowercaseChar() }.toString()
                else -> key.label
            }
        }
        keyboardView.setShiftVisual(shiftOn)
        keyboardView.invalidateAllKeys()
    }

    private fun clearBuffers(reason: String) {
        Logger.d("buffer", "clear reason=$reason internal='$internalBuffer' external='${composer.text()}'")
        if (!composer.isEmpty()) {
            setComposing("")
        }
        composer.clear()
        internalBuffer.setLength(0)
        if (::candidateContainer.isInitialized) refreshCandidates()
    }

    private fun appendInternalSource(source: String) {
        internalBuffer.append(CandidateEngine.normalizeInputSource(source))
        trimInternalToMax()
    }

    private fun trimInternalToMax() {
        val externalCount = codePointCount(CandidateEngine.normalizeInputSource(composer.text()))
        while (codePointCount(internalBuffer.toString()) + externalCount > 9 && internalBuffer.isNotEmpty()) {
            val next = internalBuffer.offsetByCodePoints(0, 1)
            internalBuffer.delete(0, next)
        }
    }

    private fun selfEdit(block: () -> Unit) {
        selfChanging = true
        try { block() } finally {
            handler.postDelayed({ selfChanging = false }, 80L)
        }
    }

    companion object {
        private const val ENTER_CODE = -84
        private const val SPACE_CODE = 32
        private const val KEY_JP_QUESTION = 65311
        private const val KEY_JP_EXCLAMATION = 65281
        private const val KEY_JP_COMMA = 65292
        private const val KEY_JP_PERIOD = 12290

        private fun koreanJamoForCode(code: Int, shifted: Boolean): Char? {
            return when (code) {
                113 -> if (shifted) 'ㅃ' else 'ㅂ'
                119 -> if (shifted) 'ㅉ' else 'ㅈ'
                101 -> if (shifted) 'ㄸ' else 'ㄷ'
                114 -> if (shifted) 'ㄲ' else 'ㄱ'
                116 -> if (shifted) 'ㅆ' else 'ㅅ'
                121 -> 'ㅛ'
                117 -> 'ㅕ'
                105 -> 'ㅑ'
                111 -> if (shifted) 'ㅒ' else 'ㅐ'
                112 -> if (shifted) 'ㅖ' else 'ㅔ'
                97 -> 'ㅁ'
                115 -> 'ㄴ'
                100 -> 'ㅇ'
                102 -> 'ㄹ'
                103 -> 'ㅎ'
                104 -> 'ㅗ'
                106 -> 'ㅓ'
                107 -> 'ㅏ'
                108 -> 'ㅣ'
                122 -> 'ㅋ'
                120 -> 'ㅌ'
                99 -> 'ㅊ'
                118 -> 'ㅍ'
                98 -> 'ㅠ'
                110 -> 'ㅜ'
                109 -> 'ㅡ'
                else -> null
            }
        }

        private fun codePointCount(text: String): Int = text.codePointCount(0, text.length)
        private fun removeLastCodePoint(sb: StringBuilder) {
            if (sb.isEmpty()) return
            val start = sb.offsetByCodePoints(sb.length, -1)
            sb.delete(start, sb.length)
        }
    }
}
