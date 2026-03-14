package com.hanpin.ime

class HangulComposer(
    private val maxCodePoints: Int = 11,
) {
    private val raw = mutableListOf<Char>()

    fun append(char: Char): Boolean {
        val next = raw + char
        if (compose(next).codePointCount() > maxCodePoints) {
            return false
        }
        raw += char
        return true
    }

    fun backspace(): Boolean {
        if (raw.isEmpty()) return false
        raw.removeAt(raw.lastIndex)
        return true
    }

    fun clear() {
        raw.clear()
    }

    fun isEmpty(): Boolean = raw.isEmpty()

    fun composedText(): String = compose(raw)

    fun codePointCount(): Int = composedText().codePointCount()

    private fun compose(sequence: List<Char>): String {
        val out = StringBuilder()
        var choseong: Char? = null
        var jungseong: Char? = null
        var jongseong: Char? = null

        fun flush() {
            when {
                choseong != null && jungseong != null -> {
                    out.append(composeSyllable(choseong!!, jungseong!!, jongseong))
                }
                choseong != null -> out.append(choseong)
                jungseong != null -> out.append(jungseong)
            }
            choseong = null
            jungseong = null
            jongseong = null
        }

        for (char in sequence) {
            when {
                isConsonant(char) -> {
                    when {
                        choseong == null && jungseong == null -> {
                            choseong = char
                        }
                        choseong != null && jungseong == null -> {
                            flush()
                            choseong = char
                        }
                        choseong != null && jungseong != null && jongseong == null -> {
                            jongseong = char
                        }
                        choseong != null && jungseong != null && jongseong != null -> {
                            val merged = mergeJongseong(jongseong!!, char)
                            if (merged != null) {
                                jongseong = merged
                            } else {
                                flush()
                                choseong = char
                            }
                        }
                        else -> {
                            flush()
                            choseong = char
                        }
                    }
                }

                isVowel(char) -> {
                    when {
                        choseong == null && jungseong == null -> {
                            jungseong = char
                        }
                        choseong == null && jungseong != null -> {
                            val merged = mergeJungseong(jungseong!!, char)
                            if (merged != null) {
                                jungseong = merged
                            } else {
                                flush()
                                jungseong = char
                            }
                        }
                        choseong != null && jungseong == null -> {
                            jungseong = char
                        }
                        choseong != null && jungseong != null && jongseong == null -> {
                            val merged = mergeJungseong(jungseong!!, char)
                            if (merged != null) {
                                jungseong = merged
                            } else {
                                flush()
                                jungseong = char
                            }
                        }
                        choseong != null && jungseong != null && jongseong != null -> {
                            val split = splitJongseong(jongseong!!)
                            if (split != null) {
                                out.append(composeSyllable(choseong!!, jungseong!!, split.first))
                                choseong = split.second
                                jungseong = char
                                jongseong = null
                            } else {
                                val moved = jongseong!!
                                out.append(composeSyllable(choseong!!, jungseong!!, null))
                                choseong = moved
                                jungseong = char
                                jongseong = null
                            }
                        }
                    }
                }

                else -> {
                    flush()
                    out.append(char)
                }
            }
        }

        flush()
        return out.toString()
    }

    private fun composeSyllable(choseong: Char, jungseong: Char, jongseong: Char?): Char {
        val choIndex = CHOSEONG_INDEX[choseong] ?: return choseong
        val jungIndex = JUNGSEONG_INDEX[jungseong] ?: return jungseong
        val jongIndex = jongseong?.let { JONGSEONG_INDEX[it] } ?: 0
        return (0xAC00 + (choIndex * 21 * 28) + (jungIndex * 28) + jongIndex).toChar()
    }

    companion object {
        private val CHOSEONG_INDEX = mapOf(
            'ㄱ' to 0, 'ㄲ' to 1, 'ㄴ' to 2, 'ㄷ' to 3, 'ㄸ' to 4, 'ㄹ' to 5, 'ㅁ' to 6,
            'ㅂ' to 7, 'ㅃ' to 8, 'ㅅ' to 9, 'ㅆ' to 10, 'ㅇ' to 11, 'ㅈ' to 12, 'ㅉ' to 13,
            'ㅊ' to 14, 'ㅋ' to 15, 'ㅌ' to 16, 'ㅍ' to 17, 'ㅎ' to 18,
        )

        private val JUNGSEONG_INDEX = mapOf(
            'ㅏ' to 0, 'ㅐ' to 1, 'ㅑ' to 2, 'ㅒ' to 3, 'ㅓ' to 4, 'ㅔ' to 5, 'ㅕ' to 6,
            'ㅖ' to 7, 'ㅗ' to 8, 'ㅘ' to 9, 'ㅙ' to 10, 'ㅚ' to 11, 'ㅛ' to 12, 'ㅜ' to 13,
            'ㅝ' to 14, 'ㅞ' to 15, 'ㅟ' to 16, 'ㅠ' to 17, 'ㅡ' to 18, 'ㅢ' to 19, 'ㅣ' to 20,
        )

        private val JONGSEONG_INDEX = mapOf(
            'ㄱ' to 1, 'ㄲ' to 2, 'ㄳ' to 3, 'ㄴ' to 4, 'ㄵ' to 5, 'ㄶ' to 6, 'ㄷ' to 7,
            'ㄹ' to 8, 'ㄺ' to 9, 'ㄻ' to 10, 'ㄼ' to 11, 'ㄽ' to 12, 'ㄾ' to 13, 'ㄿ' to 14,
            'ㅀ' to 15, 'ㅁ' to 16, 'ㅂ' to 17, 'ㅄ' to 18, 'ㅅ' to 19, 'ㅆ' to 20,
            'ㅇ' to 21, 'ㅈ' to 22, 'ㅊ' to 23, 'ㅋ' to 24, 'ㅌ' to 25, 'ㅍ' to 26, 'ㅎ' to 27,
        )

        private val JUNG_MERGE = mapOf(
            ('ㅗ' to 'ㅏ') to 'ㅘ',
            ('ㅗ' to 'ㅐ') to 'ㅙ',
            ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅜ' to 'ㅓ') to 'ㅝ',
            ('ㅜ' to 'ㅔ') to 'ㅞ',
            ('ㅜ' to 'ㅣ') to 'ㅟ',
            ('ㅡ' to 'ㅣ') to 'ㅢ',
        )

        private val JONG_MERGE = mapOf(
            ('ㄱ' to 'ㅅ') to 'ㄳ',
            ('ㄴ' to 'ㅈ') to 'ㄵ',
            ('ㄴ' to 'ㅎ') to 'ㄶ',
            ('ㄹ' to 'ㄱ') to 'ㄺ',
            ('ㄹ' to 'ㅁ') to 'ㄻ',
            ('ㄹ' to 'ㅂ') to 'ㄼ',
            ('ㄹ' to 'ㅅ') to 'ㄽ',
            ('ㄹ' to 'ㅌ') to 'ㄾ',
            ('ㄹ' to 'ㅍ') to 'ㄿ',
            ('ㄹ' to 'ㅎ') to 'ㅀ',
            ('ㅂ' to 'ㅅ') to 'ㅄ',
        )

        private val JONG_SPLIT = JONG_MERGE.entries.associate { it.value to it.key }
        private val CONSONANTS = CHOSEONG_INDEX.keys + JONGSEONG_INDEX.keys
        private val VOWELS = JUNGSEONG_INDEX.keys

        fun isConsonant(char: Char): Boolean = char in CONSONANTS
        fun isVowel(char: Char): Boolean = char in VOWELS
        fun mergeJungseong(left: Char, right: Char): Char? = JUNG_MERGE[left to right]
        fun mergeJongseong(left: Char, right: Char): Char? = JONG_MERGE[left to right]
        fun splitJongseong(char: Char): Pair<Char, Char>? = JONG_SPLIT[char]
    }
}
