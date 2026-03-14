package com.hanpin.ime

import kotlin.math.ln

class NGram {
    private val unigram = LinkedHashMap<String, Int>()
    private val bigram = LinkedHashMap<String, Int>()

    fun train(phrases: Iterable<String>) {
        for (phrase in phrases) {
            val units = phrase.codePointStrings()
            if (units.isEmpty()) continue
            units.forEach { unit -> unigram[unit] = (unigram[unit] ?: 0) + 1 }
            for (index in 0 until units.lastIndex) {
                val key = units[index] + units[index + 1]
                bigram[key] = (bigram[key] ?: 0) + 1
            }
        }
    }

    fun rankSingle(chars: List<String>, maxResults: Int = 10): List<String> {
        return chars
            .distinct()
            .sortedByDescending { unigramScore(it) }
            .take(maxResults)
    }

    fun generate(
        units: List<String>,
        unitToChars: Map<String, List<String>>,
        maxResults: Int = 10,
    ): List<String> {
        if (units.isEmpty()) return emptyList()
        if (units.size == 1) return rankSingle(unitToChars[units.first()].orEmpty(), maxResults)

        val options = units.map { unit ->
            unitToChars[unit]
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.take(10)
                .orEmpty()
        }
        if (options.any { it.isEmpty() }) return emptyList()

        data class Beam(val text: String, val score: Double)
        var beams = listOf(Beam("", 0.0))

        options.forEachIndexed { index, candidates ->
            val next = mutableMapOf<String, Beam>()
            for (beam in beams) {
                for ((rank, char) in candidates.withIndex()) {
                    val score = beam.score + scoreAppend(beam.text, char, rank)
                    val text = beam.text + char
                    val current = next[text]
                    if (current == null || score > current.score) {
                        next[text] = Beam(text, score)
                    }
                }
            }
            beams = next.values
                .sortedByDescending { it.score }
                .take(if (index == 0) 25 else 50)
        }

        return beams.map { it.text }.distinct().take(maxResults)
    }

    private fun scoreAppend(prefix: String, char: String, rank: Int): Double {
        val unigramWeight = unigramScore(char)
        val bigramWeight = if (prefix.isNotEmpty()) {
            bigramScore(prefix.takeLast(1) + char)
        } else {
            0.0
        }
        val orderPenalty = rank * 0.18
        return unigramWeight * 0.35 + bigramWeight * 1.0 - orderPenalty
    }

    private fun unigramScore(char: String): Double = ln(((unigram[char] ?: 0) + 1).toDouble())
    private fun bigramScore(pair: String): Double = ln(((bigram[pair] ?: 0) + 1).toDouble())
}
