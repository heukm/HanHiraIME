package com.hanpin.ime

class PinyinProcessor(
    private val legalSyllables: Set<String>,
    private val unitToChars: Map<String, List<String>> = emptyMap(),
) {
    private val maxSyllableLength = legalSyllables.maxOfOrNull { it.length } ?: 6

    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder(raw.length)
        raw.lowercase().forEach { ch ->
            when (ch) {
                'ü', 'ǖ', 'ǘ', 'ǚ', 'ǜ' -> out.append('v')
                'ā', 'á', 'ǎ', 'à' -> out.append('a')
                'ē', 'é', 'ě', 'è' -> out.append('e')
                'ī', 'í', 'ǐ', 'ì' -> out.append('i')
                'ō', 'ó', 'ǒ', 'ò' -> out.append('o')
                'ū', 'ú', 'ǔ', 'ù' -> out.append('u')
                'ń', 'ň', 'ǹ' -> out.append('n')
                '\'', ' ', '-', '_' -> Unit
                else -> if (ch in 'a'..'z' || ch == 'v') out.append(ch)
            }
        }
        return out.toString()
    }

    fun segment(rawInput: String, maxResults: Int = 8, maxChars: Int = 11): List<List<String>> {
        val input = normalize(rawInput)
        if (input.isEmpty()) return emptyList()
        val memo = HashMap<Int, List<List<String>>>()

        fun dfs(index: Int): List<List<String>> {
            memo[index]?.let { return it }
            if (index == input.length) {
                return listOf(emptyList())
            }

            val candidates = mutableListOf<List<String>>()
            val upperBound = minOf(input.length, index + maxSyllableLength)
            for (end in upperBound downTo index + 1) {
                val token = input.substring(index, end)
                if (token !in legalSyllables) continue
                for (tail in dfs(end)) {
                    if (tail.size + 1 <= maxChars) {
                        candidates += listOf(token) + tail
                    }
                }
            }

            val ranked = candidates
                .distinct()
                .sortedByDescending(::score)
                .take(maxResults * 4)
            memo[index] = ranked
            return ranked
        }

        return dfs(0)
            .sortedByDescending(::score)
            .distinct()
            .take(maxResults)
    }

    private fun score(tokens: List<String>): Double {
        if (tokens.isEmpty()) return Double.NEGATIVE_INFINITY
        var score = 0.0
        for (token in tokens) {
            val mapped = unitToChars[token]?.size ?: 0
            score += (token.length * token.length).toDouble()
            score += kotlin.math.ln((mapped + 1).toDouble())
        }
        score -= tokens.size * 0.75
        return score
    }
}
