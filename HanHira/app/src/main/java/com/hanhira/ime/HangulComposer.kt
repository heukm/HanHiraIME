package com.hanhira.ime

/**
 * 두벌식 호환자모 입력을 그대로 받아 한글 음절을 합성한다.
 * 표준자모 변환을 거치지 않으므로 키 입력 중 변환 오버헤드가 없다.
 */
class HangulComposer {
    private val completed = StringBuilder()
    private var l: Char? = null
    private var v: Char? = null
    private var t: Char? = null

    fun isEmpty(): Boolean = completed.isEmpty() && l == null && v == null && t == null

    fun clear() {
        completed.setLength(0)
        l = null
        v = null
        t = null
    }

    fun text(): String = completed.toString() + currentSyllableOrJamo()

    fun appendLongMark() {
        flushCurrent()
        completed.append('ー')
    }

    fun inputJamo(jamo: Char) {
        when {
            isVowel(jamo) -> inputVowel(jamo)
            isConsonant(jamo) -> inputConsonant(jamo)
            else -> {
                flushCurrent()
                completed.append(jamo)
            }
        }
    }

    fun backspace(): Boolean {
        if (t != null) {
            val prev = splitFinal[t!!]
            if (prev != null) {
                t = prev.first
            } else {
                t = null
            }
            return true
        }
        if (v != null) {
            val prev = splitVowel[v!!]
            if (prev != null) {
                v = prev.first
            } else {
                v = null
            }
            return true
        }
        if (l != null) {
            l = null
            return true
        }
        if (completed.isNotEmpty()) {
            val last = completed.offsetByCodePoints(completed.length, -1)
            completed.delete(last, completed.length)
            return true
        }
        return false
    }

    private fun inputConsonant(c: Char) {
        if (l == null && v == null) {
            l = initialFromCompat(c) ?: c
            return
        }
        if (l != null && v == null) {
            flushCurrent()
            l = initialFromCompat(c) ?: c
            return
        }
        if (l != null && v != null && t == null) {
            val asFinal = finalFromCompat(c)
            if (asFinal != null) {
                t = asFinal
            } else {
                flushCurrent()
                l = initialFromCompat(c) ?: c
            }
            return
        }
        if (l != null && v != null && t != null) {
            val combined = combineFinal[t!! to c]
            if (combined != null) {
                t = combined
            } else {
                flushCurrent()
                l = initialFromCompat(c) ?: c
            }
            return
        }
        flushCurrent()
        l = initialFromCompat(c) ?: c
    }

    private fun inputVowel(nv: Char) {
        if (l == null && v == null) {
            l = 'ㅇ'
            v = nv
            return
        }
        if (l != null && v == null) {
            v = nv
            return
        }
        if (l != null && v != null && t == null) {
            val combined = combineVowel[v!! to nv]
            if (combined != null) {
                v = combined
            } else {
                flushCurrent()
                l = 'ㅇ'
                v = nv
            }
            return
        }
        if (l != null && v != null && t != null) {
            val finalChar = t!!
            val split = splitFinalForMove[finalChar]
            if (split != null) {
                t = split.first
                flushCurrent()
                l = initialFromCompat(split.second) ?: 'ㅇ'
                v = nv
            } else {
                t = null
                flushCurrentWith(l, v, null)
                l = initialFromCompat(finalChar) ?: 'ㅇ'
                v = nv
            }
            return
        }
    }

    private fun flushCurrent() {
        val s = currentSyllableOrJamo()
        if (s.isNotEmpty()) completed.append(s)
        l = null
        v = null
        t = null
    }

    private fun flushCurrentWith(fl: Char?, fv: Char?, ft: Char?) {
        if (fl == null) return
        if (fv == null) {
            completed.append(fl)
            return
        }
        val li = L_LIST.indexOf(fl)
        val vi = V_LIST.indexOf(fv)
        val ti = if (ft == null) 0 else T_LIST.indexOf(ft)
        if (li >= 0 && vi >= 0 && ti >= 0) {
            completed.append((0xAC00 + ((li * 21 + vi) * 28 + ti)).toChar())
        } else {
            completed.append(fl)
            completed.append(fv)
            if (ft != null) completed.append(ft)
        }
    }

    private fun currentSyllableOrJamo(): String {
        val cl = l ?: return ""
        val cv = v
        if (cv == null) return cl.toString()
        val li = L_LIST.indexOf(cl)
        val vi = V_LIST.indexOf(cv)
        val ti = if (t == null) 0 else T_LIST.indexOf(t!!)
        return if (li >= 0 && vi >= 0 && ti >= 0) {
            (0xAC00 + ((li * 21 + vi) * 28 + ti)).toChar().toString()
        } else {
            buildString {
                append(cl)
                append(cv)
                if (t != null) append(t)
            }
        }
    }

    companion object {
        private val L_LIST = listOf('ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ')
        private val V_LIST = listOf('ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ','ㅙ','ㅚ','ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ')
        private val T_LIST = listOf('\u0000','ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ','ㄻ','ㄼ','ㄽ','ㄾ','ㄿ','ㅀ','ㅁ','ㅂ','ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ')
        private val CONSONANTS = setOf('ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ')
        private val FINALS = T_LIST.drop(1).toSet()

        private val combineVowel = mapOf(
            'ㅗ' to 'ㅏ' to 'ㅘ', 'ㅗ' to 'ㅐ' to 'ㅙ', 'ㅗ' to 'ㅣ' to 'ㅚ',
            'ㅜ' to 'ㅓ' to 'ㅝ', 'ㅜ' to 'ㅔ' to 'ㅞ', 'ㅜ' to 'ㅣ' to 'ㅟ',
            'ㅡ' to 'ㅣ' to 'ㅢ'
        )
        private val splitVowel = mapOf(
            'ㅘ' to ('ㅗ' to 'ㅏ'), 'ㅙ' to ('ㅗ' to 'ㅐ'), 'ㅚ' to ('ㅗ' to 'ㅣ'),
            'ㅝ' to ('ㅜ' to 'ㅓ'), 'ㅞ' to ('ㅜ' to 'ㅔ'), 'ㅟ' to ('ㅜ' to 'ㅣ'),
            'ㅢ' to ('ㅡ' to 'ㅣ')
        )
        private val combineFinal = mapOf(
            'ㄱ' to 'ㅅ' to 'ㄳ', 'ㄴ' to 'ㅈ' to 'ㄵ', 'ㄴ' to 'ㅎ' to 'ㄶ',
            'ㄹ' to 'ㄱ' to 'ㄺ', 'ㄹ' to 'ㅁ' to 'ㄻ', 'ㄹ' to 'ㅂ' to 'ㄼ',
            'ㄹ' to 'ㅅ' to 'ㄽ', 'ㄹ' to 'ㅌ' to 'ㄾ', 'ㄹ' to 'ㅍ' to 'ㄿ',
            'ㄹ' to 'ㅎ' to 'ㅀ', 'ㅂ' to 'ㅅ' to 'ㅄ'
        )
        private val splitFinal = combineFinal.entries.associate { it.value to it.key }
        private val splitFinalForMove = splitFinal

        fun isVowel(c: Char): Boolean = V_LIST.contains(c)
        fun isConsonant(c: Char): Boolean = CONSONANTS.contains(c) || FINALS.contains(c)
        fun initialFromCompat(c: Char): Char? = if (L_LIST.contains(c)) c else null
        fun finalFromCompat(c: Char): Char? = if (FINALS.contains(c) && c !in setOf('ㄸ','ㅃ','ㅉ')) c else null
    }
}
