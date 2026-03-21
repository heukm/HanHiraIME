package com.hanpin.ime

class CandidateEngine(
    private val modelRunner: ModelRunner,
) {
    fun suggestHangul(query: String): CandidateResult {
        modelRunner.ensureLoaded()
        val bundle = modelRunner.hangulBundle
        val normalized = query.replace(" ", "")
        if (normalized.isBlank()) return CandidateResult()
        val units = normalized.codePointStrings().take(11)
        val direct = bundle.dictionary[normalized].orEmpty().distinct()

        if (units.size == 1) {
            val directSingle = (direct + bundle.singleMap[normalized].orEmpty())
                .distinct()
                .filter { matchesHangulReading(normalized, bundle, it, allowDictionaryExact = true) }
            if (directSingle.isNotEmpty()) {
                Logger.candidate("Hangul single direct hit: $normalized -> ${directSingle.size}")
                return CandidateResult(candidates = directSingle, direct = directSingle)
            }
            val neuralPool = predictOrHeuristic(bundle, units)
            val ngramPool = modelRunner.ngram.rankSingle(bundle.vocab.unitToChars[normalized].orEmpty(), 10)
            val filtered = filterHangulCandidates(normalized, bundle, (neuralPool + ngramPool).distinct())
            Logger.candidate("Hangul single filtered '$normalized' kept=${filtered.size} from=${(neuralPool + ngramPool).distinct().size}")
            return CandidateResult(candidates = filtered, neural = filtered, ngram = emptyList())
        }

        val neuralPool = predictOrHeuristic(bundle, units)
        val neural = filterHangulCandidates(normalized, bundle, neuralPool)
            .filterNot { it in direct }
            .distinct()
            .take(6)
        val ngramPool = filterHangulCandidates(
            normalized,
            bundle,
            modelRunner.ngram.generate(units, bundle.vocab.unitToChars, 10),
        )
        val merged = (direct + neural + ngramPool).distinct().take(13)
        Logger.candidate(
            "Hangul phrase '$normalized' direct=${direct.size} neural=${neural.size} ngram=${ngramPool.size} merged=${merged.size}",
        )
        return CandidateResult(candidates = merged, direct = direct, neural = neural, ngram = ngramPool)
    }

    fun suggestPinyin(rawInput: String): CandidateResult {
        modelRunner.ensureLoaded()
        val bundle = modelRunner.pinyinBundle
        val processor = PinyinProcessor(bundle.vocab.legalUnits, bundle.vocab.unitToChars)
        val normalized = processor.normalize(rawInput)
        if (normalized.isBlank()) return CandidateResult()

        val segmentations = processor.segment(normalized, maxResults = 8, maxChars = 11)
        if (segmentations.isEmpty()) {
            return CandidateResult()
        }

        val best = segmentations.first()
        if (best.size == 1) {
            val syllable = best.first()
            val directSingle = buildList {
                addAll(bundle.singleMap[syllable].orEmpty())
                addAll(bundle.dictionary[syllable].orEmpty())
            }
                .distinct()
                .filter { matchesPinyinReading(listOf(syllable), bundle, it, allowDictionaryExact = true) }
            if (directSingle.isNotEmpty()) {
                Logger.candidate("Pinyin single direct hit: $syllable -> ${directSingle.size}")
                return CandidateResult(candidates = directSingle, segments = best, direct = directSingle)
            }
            val neuralPool = segmentations.flatMap { predictOrHeuristic(bundle, it) }.distinct().take(10)
            val ngramPool = modelRunner.ngram.rankSingle(bundle.vocab.unitToChars[syllable].orEmpty(), 10)
            val filtered = filterPinyinCandidates(segmentations, bundle, (neuralPool + ngramPool).distinct())
            Logger.candidate("Pinyin single filtered '$syllable' kept=${filtered.size} from=${(neuralPool + ngramPool).distinct().size}")
            return CandidateResult(candidates = filtered, segments = best, neural = filtered, ngram = emptyList())
        }

        val direct = buildList {
            for (segments in segmentations) {
                addAll(bundle.dictionary[segments.joinToString(separator = "")].orEmpty())
            }
        }.distinct()

        val neuralPool = segmentations.flatMap { predictOrHeuristic(bundle, it) }.distinct().take(10)
        val neural = filterPinyinCandidates(segmentations, bundle, neuralPool)
            .filterNot { it in direct }
            .take(3)
        val ngramPool = filterPinyinCandidates(
            segmentations,
            bundle,
            segmentations.flatMap { modelRunner.ngram.generate(it, bundle.vocab.unitToChars, 10) }.distinct(),
        )
        val merged = (direct + neural + ngramPool).distinct().take(13)
        Logger.candidate(
            "Pinyin phrase '$normalized' direct=${direct.size} neural=${neural.size} ngram=${ngramPool.size} merged=${merged.size}",
        )
        return CandidateResult(candidates = merged, segments = best, direct = direct, neural = neural, ngram = ngramPool)
    }

    private fun predictOrHeuristic(
        bundle: ModelRunner.SourceBundle,
        units: List<String>,
    ): List<String> {
        val predicted = bundle.predictor?.predict(units, maxResults = 10).orEmpty()
        if (predicted.isNotEmpty()) return predicted.distinct().take(10)
        return modelRunner.ngram.generate(units, bundle.vocab.unitToChars, 10)
    }

    private fun filterHangulCandidates(
        normalized: String,
        bundle: ModelRunner.SourceBundle,
        candidates: List<String>,
    ): List<String> {
        return candidates.filter { matchesHangulReading(normalized, bundle, it, allowDictionaryExact = true) }
    }

    private fun filterPinyinCandidates(
        segmentations: List<List<String>>,
        bundle: ModelRunner.SourceBundle,
        candidates: List<String>,
    ): List<String> {
        return candidates.filter { candidate ->
            segmentations.any { segments -> matchesPinyinReading(segments, bundle, candidate, allowDictionaryExact = true) }
        }
    }

    private fun matchesHangulReading(
        normalized: String,
        bundle: ModelRunner.SourceBundle,
        candidate: String,
        allowDictionaryExact: Boolean,
    ): Boolean {
        if (candidate.isBlank()) return false
        if (allowDictionaryExact && bundle.dictionary[normalized].orEmpty().contains(candidate)) return true
        if (normalized.codePointCount() == 1) {
            val allowedSingles = buildSet {
                addAll(bundle.singleMap[normalized].orEmpty())
                addAll(bundle.vocab.unitToChars[normalized].orEmpty())
                addAll(bundle.dictionary[normalized].orEmpty())
            }
            return candidate in allowedSingles
        }

        val queryUnits = normalized.codePointStrings()
        val candidateChars = candidate.codePointStrings()
        if (candidateChars.size != queryUnits.size) return false

        for (index in candidateChars.indices) {
            val reading = bundle.vocab.charToUnit[candidateChars[index]] ?: return false
            if (reading != queryUnits[index]) return false
        }
        return true
    }

    private fun matchesPinyinReading(
        segments: List<String>,
        bundle: ModelRunner.SourceBundle,
        candidate: String,
        allowDictionaryExact: Boolean,
    ): Boolean {
        if (candidate.isBlank()) return false
        val joined = segments.joinToString(separator = "")
        if (allowDictionaryExact && bundle.dictionary[joined].orEmpty().contains(candidate)) return true
        if (segments.size == 1) {
            val syllable = segments.first()
            val allowedSingles = buildSet {
                addAll(bundle.singleMap[syllable].orEmpty())
                addAll(bundle.vocab.unitToChars[syllable].orEmpty())
                addAll(bundle.dictionary[syllable].orEmpty())
            }
            return candidate in allowedSingles
        }

        val candidateChars = candidate.codePointStrings()
        if (candidateChars.size != segments.size) return false

        for (index in candidateChars.indices) {
            val reading = bundle.vocab.charToUnit[candidateChars[index]] ?: return false
            if (reading != segments[index]) return false
        }
        return true
    }
}

data class CandidateResult(
    val candidates: List<String> = emptyList(),
    val segments: List<String> = emptyList(),
    val direct: List<String> = emptyList(),
    val neural: List<String> = emptyList(),
    val ngram: List<String> = emptyList(),
)

internal fun String.codePointCount(): Int = Character.codePointCount(this, 0, length)

internal fun String.codePointStrings(): List<String> {
    if (isEmpty()) return emptyList()
    val out = ArrayList<String>()
    var index = 0
    while (index < length) {
        val cp = Character.codePointAt(this, index)
        out += String(Character.toChars(cp))
        index += Character.charCount(cp)
    }
    return out
}
