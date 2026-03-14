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
            val directSingle = (direct + bundle.singleMap[normalized].orEmpty()).distinct()
            if (directSingle.isNotEmpty()) {
                Logger.candidate("Hangul single direct hit: $normalized -> ${directSingle.size}")
                return CandidateResult(candidates = directSingle, direct = directSingle)
            }
            val neuralPool = predictOrHeuristic(bundle, units)
            val ngramPool = modelRunner.ngram.rankSingle(bundle.vocab.unitToChars[normalized].orEmpty(), 10)
            val merged = (neuralPool + ngramPool).distinct()
            return CandidateResult(candidates = merged, neural = neuralPool, ngram = ngramPool)
        }

        val neuralPool = predictOrHeuristic(bundle, units)
        val neural = neuralPool.filterNot { it in direct }.distinct().take(6)
        val ngramPool = modelRunner.ngram.generate(units, bundle.vocab.unitToChars, 10)
        val merged = (direct + neural + ngramPool).distinct().take(13)
        Logger.candidate("Hangul phrase '$normalized' direct=${direct.size} neural=${neural.size} ngram=${ngramPool.size}")
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
            }.distinct()
            if (directSingle.isNotEmpty()) {
                Logger.candidate("Pinyin single direct hit: $syllable -> ${directSingle.size}")
                return CandidateResult(candidates = directSingle, segments = best, direct = directSingle)
            }
            val neuralPool = segmentations.flatMap { predictOrHeuristic(bundle, it) }.distinct().take(10)
            val ngramPool = modelRunner.ngram.rankSingle(bundle.vocab.unitToChars[syllable].orEmpty(), 10)
            val merged = (neuralPool + ngramPool).distinct()
            return CandidateResult(candidates = merged, segments = best, neural = neuralPool, ngram = ngramPool)
        }

        val direct = buildList {
            for (segments in segmentations) {
                addAll(bundle.dictionary[segments.joinToString(separator = "")].orEmpty())
            }
        }.distinct()

        val neuralPool = segmentations.flatMap { predictOrHeuristic(bundle, it) }.distinct().take(10)
        val neural = neuralPool.filterNot { it in direct }.take(3)
        val ngramPool = segmentations
            .flatMap { modelRunner.ngram.generate(it, bundle.vocab.unitToChars, 10) }
            .distinct()
        val merged = (direct + neural + ngramPool).distinct().take(13)
        Logger.candidate("Pinyin phrase '$normalized' direct=${direct.size} neural=${neural.size} ngram=${ngramPool.size}")
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
