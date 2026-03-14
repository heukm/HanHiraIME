package com.hanpin.ime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class ModelRunner(
    private val context: Context,
) {
    @Volatile
    private var loaded = false

    lateinit var hangulBundle: SourceBundle
        private set
    lateinit var pinyinBundle: SourceBundle
        private set
    lateinit var ngram: NGram
        private set

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return

            val hangulDictionary = parseDictionaryCsv(
                assetNames = listOf(
                    "combined_korean_chinese2.csv",
                    "hangul_hanzi_dict2.csv",
                    "combined_korean_chinese.csv",
                ),
                kind = DictionaryKind.HANGUL,
            )
            val pinyinDictionary = parseDictionaryCsv(
                assetNames = listOf(
                    "pinyin_hanzi_dict2.csv",
                    "pinyin_hanzi_dict.csv",
                ),
                kind = DictionaryKind.PINYIN,
            )

            val hangulVocab = parseVocab(
                assetNames = listOf("kohan_vocab2.json", "kohan_vocab.json"),
                mode = Mode.HANGUL,
                dictionary = hangulDictionary,
            )
            val pinyinVocab = parseVocab(
                assetNames = listOf("pinyin_vocab2.json"),
                mode = Mode.PINYIN,
                dictionary = pinyinDictionary,
            )

            hangulBundle = SourceBundle(
                dictionary = hangulDictionary,
                singleMap = buildSingleMap(hangulDictionary, hangulVocab.unitToChars),
                vocab = hangulVocab,
                predictor = createPredictor(
                    assetNames = listOf("kohan_model_lite3.ptl", "kohan_model_lite2.pt"),
                    vocab = hangulVocab,
                ),
            )
            pinyinBundle = SourceBundle(
                dictionary = pinyinDictionary,
                singleMap = buildSingleMap(pinyinDictionary, pinyinVocab.unitToChars),
                vocab = pinyinVocab,
                predictor = createPredictor(
                    assetNames = listOf("pinyin_model_lite3.ptl", "pinyin_model_lite2.pt"),
                    vocab = pinyinVocab,
                ),
            )

            ngram = NGram().apply {
                train(
                    sequenceOf(
                        hangulDictionary.values.flatten(),
                        pinyinDictionary.values.flatten(),
                        hangulVocab.unitToChars.values.flatten(),
                        pinyinVocab.unitToChars.values.flatten(),
                    ).flatten().filter { it.isNotBlank() }.asIterable()
                )
            }

            loaded = true
        }
    }

    private fun createPredictor(assetNames: List<String>, vocab: VocabSpec): TorchPredictor? {
        val assetName = firstExistingAsset(assetNames) ?: assetNames.firstOrNull() ?: return null
        return try {
            TorchPredictor(context, assetName, vocab)
        } catch (throwable: Throwable) {
            Logger.error("Unable to create predictor for $assetName", throwable)
            null
        }
    }

    private fun parseVocab(
        assetNames: List<String>,
        mode: Mode,
        dictionary: Map<String, List<String>>,
    ): VocabSpec {
        val assetName = firstExistingAsset(assetNames)
        if (assetName == null) {
            Logger.error("No vocab asset found from $assetNames")
            return fallbackVocab(dictionary)
        }

        return try {
            val text = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val meta = json.optJSONObject("meta")
            val config = json.optJSONObject("config")

            val tokenLen = firstNonZero(
                meta?.optInt("token"),
                config?.optInt("token"),
                config?.optInt("tokenLen"),
                json.optInt("token"),
                12,
            )
            val stride = firstNonZero(
                meta?.optInt("stride"),
                config?.optInt("stride"),
                json.optInt("stride"),
                10,
            )
            val maxInput = firstNonZero(
                meta?.optInt("max_input_len"),
                config?.optInt("max_input_len"),
                config?.optInt("inference_max_input"),
                json.optInt("max_input_len"),
                11,
            )

            val singleUnits = when (mode) {
                Mode.HANGUL -> json.optJSONArray("hangul_single_list")?.toStringList().orEmpty()
                Mode.PINYIN -> json.optJSONArray("pinyin_single_list")?.toStringList().orEmpty()
            }
            val allUnits = when (mode) {
                Mode.HANGUL -> {
                    json.optJSONArray("hangul")?.toStringList()
                        ?: json.optJSONArray("korean")?.toStringList()
                        ?: emptyList()
                }
                Mode.PINYIN -> json.optJSONArray("pinyin")?.toStringList().orEmpty()
            }

            val inputTokens = when (mode) {
                Mode.HANGUL -> {
                    json.optJSONObject("hangul")?.toStringIntMap()
                        ?: json.optJSONObject("korean")?.toStringIntMap()
                        ?: json.optJSONObject("maps")?.optJSONObject("korean")?.toStringIntMap()
                        ?: json.optJSONObject("input_vocabs")?.optJSONObject("korean")?.toStringIntMap()
                        ?: buildInputTokenMap(if (singleUnits.isNotEmpty()) singleUnits else allUnits)
                }
                Mode.PINYIN -> {
                    json.optJSONObject("pinyin")?.toStringIntMap()
                        ?: json.optJSONObject("maps")?.optJSONObject("pinyin")?.toStringIntMap()
                        ?: json.optJSONObject("input_vocabs")?.optJSONObject("pinyin")?.toStringIntMap()
                        ?: buildInputTokenMap(if (singleUnits.isNotEmpty()) singleUnits else allUnits)
                }
            }

            val outputTokens = when {
                json.has("hanzi_list") -> json.getJSONArray("hanzi_list").toStringList()
                json.opt("hanzi") is JSONArray -> json.getJSONArray("hanzi").toStringList()
                json.opt("hanzi") is JSONObject -> json.getJSONObject("hanzi").toOrderedListByValue()
                else -> emptyList()
            }

            val unitToCharsFromMap = when (mode) {
                Mode.HANGUL -> {
                    json.optJSONObject("kor2chars")?.toStringListMap()
                        ?: json.optJSONObject("maps")?.optJSONObject("kor2chars")?.toStringListMap()
                        ?: json.optJSONArray("maps")?.toUnitCharsMap("hangul")
                }
                Mode.PINYIN -> {
                    json.optJSONObject("py2chars")?.toStringListMap()
                        ?: json.optJSONObject("maps")?.optJSONObject("py2chars")?.toStringListMap()
                        ?: json.optJSONArray("maps")?.toUnitCharsMap("pinyin")
                }
            }.orEmpty()

            val charToUnit = when (mode) {
                Mode.HANGUL -> json.optJSONObject("char2kor")?.toStringStringMap().orEmpty()
                Mode.PINYIN -> json.optJSONObject("char2py")?.toStringStringMap().orEmpty()
            }

            val derivedMap = deriveUnitToChars(dictionary)
            val unitToChars = mergeUnitMaps(unitToCharsFromMap, derivedMap)
            val legalUnits = when {
                singleUnits.isNotEmpty() -> singleUnits.toSet()
                allUnits.isNotEmpty() -> allUnits.toSet()
                inputTokens.isNotEmpty() -> inputTokens.keys.filterNot { it.startsWith("<") }.toSet()
                else -> unitToChars.keys
            }
            val outputSkipIds = outputTokens.mapIndexedNotNull { index, token -> index.takeIf { token.startsWith("<") } }.toSet()

            VocabSpec(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                legalUnits = legalUnits,
                unitToChars = unitToChars,
                charToUnit = if (charToUnit.isNotEmpty()) charToUnit else deriveCharToUnit(unitToChars),
                tokenLen = tokenLen.coerceAtLeast(1),
                stride = stride.coerceAtLeast(1),
                maxInferenceInput = maxInput.coerceAtLeast(1),
                padId = inputTokens["<pad>"] ?: 0,
                unkId = inputTokens["<unk>"] ?: 1,
                outputSkipIds = outputSkipIds,
            )
        } catch (throwable: Throwable) {
            Logger.error("Failed to parse vocab $assetName", throwable)
            fallbackVocab(dictionary)
        }
    }

    private fun fallbackVocab(dictionary: Map<String, List<String>>): VocabSpec {
        val fallback = deriveUnitToChars(dictionary)
        val inputTokens = buildInputTokenMap(fallback.keys.sorted())
        return VocabSpec(
            inputTokens = inputTokens,
            outputTokens = emptyList(),
            legalUnits = fallback.keys,
            unitToChars = fallback,
            charToUnit = deriveCharToUnit(fallback),
            tokenLen = 12,
            stride = 10,
            maxInferenceInput = 11,
            padId = inputTokens["<pad>"] ?: 0,
            unkId = inputTokens["<unk>"] ?: 1,
            outputSkipIds = emptySet(),
        )
    }

    private fun buildInputTokenMap(units: List<String>): Map<String, Int> {
        val ordered = units.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val out = LinkedHashMap<String, Int>(ordered.size + 2)
        out["<pad>"] = 0
        out["<unk>"] = 1
        ordered.forEachIndexed { index, unit ->
            out[unit] = index + 2
        }
        return out
    }

    private fun mergeUnitMaps(
        preferred: Map<String, List<String>>,
        fallback: Map<String, List<String>>,
    ): Map<String, List<String>> {
        if (preferred.isEmpty()) return fallback
        if (fallback.isEmpty()) return preferred
        val result = LinkedHashMap<String, LinkedHashSet<String>>()
        for ((key, values) in preferred) {
            result.getOrPut(key) { LinkedHashSet() }.addAll(values.filter { it.isNotBlank() })
        }
        for ((key, values) in fallback) {
            result.getOrPut(key) { LinkedHashSet() }.addAll(values.filter { it.isNotBlank() })
        }
        return result.mapValues { it.value.toList() }
    }

    private fun parseDictionaryCsv(assetNames: List<String>, kind: DictionaryKind): Map<String, List<String>> {
        val assetName = firstExistingAsset(assetNames)
        if (assetName == null) {
            Logger.error("No dictionary asset found from $assetNames")
            return emptyMap()
        }
        return try {
            context.assets.open(assetName).use { stream ->
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                val result = LinkedHashMap<String, LinkedHashSet<String>>()
                val lines = reader.readLines()
                if (lines.isEmpty()) return emptyMap()
                val header = parseCsvLine(lines.first()).map { it.lowercase(Locale.ROOT) }
                val hasStructuredHeader = header.contains("character") && (header.contains("pinyin") || header.contains("hangul") || header.contains("korean"))
                val startIndex = if (hasStructuredHeader) 1 else 0
                val keyIndex = when {
                    header.contains("hangul") -> header.indexOf("hangul")
                    header.contains("korean") -> header.indexOf("korean")
                    header.contains("pinyin") -> header.indexOf("pinyin")
                    else -> 0
                }
                val charIndex = if (header.contains("character")) header.indexOf("character") else 1

                for (line in lines.drop(startIndex)) {
                    if (line.isBlank()) continue
                    val row = parseCsvLine(line)
                    if (row.isEmpty()) continue
                    if (hasStructuredHeader && row.size > maxOf(keyIndex, charIndex)) {
                        val rawKey = row[keyIndex]
                        val rawChar = row[charIndex]
                        val key = normalizeKey(rawKey, kind)
                        val value = rawChar.trim()
                        if (key.isNotBlank() && value.isNotBlank()) {
                            result.getOrPut(key) { LinkedHashSet() }.add(value)
                        }
                    } else {
                        val rawKey = row.getOrNull(0).orEmpty()
                        val key = normalizeKey(rawKey, kind)
                        if (key.isBlank()) continue
                        val valueCell = row.getOrNull(1)
                            ?: row.getOrNull(2)
                            ?: row.drop(1).joinToString(";")
                        val values = splitCandidates(valueCell)
                        if (values.isEmpty()) continue
                        val bucket = result.getOrPut(key) { LinkedHashSet() }
                        values.forEach(bucket::add)
                    }
                }
                result.mapValues { it.value.toList() }
            }
        } catch (throwable: Throwable) {
            Logger.error("Failed to parse dictionary $assetName", throwable)
            emptyMap()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        if (line.isEmpty()) return emptyList()
        val normalized = line.removePrefix("﻿")
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < normalized.length) {
            val ch = normalized[index]
            when {
                ch == '"' -> {
                    if (inQuotes && index + 1 < normalized.length && normalized[index + 1] == '"') {
                        cell.append('"')
                        index += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    out += cell.toString().trim()
                    cell.setLength(0)
                }
                else -> cell.append(ch)
            }
            index += 1
        }
        out += cell.toString().trim()
        return out
    }

    private fun splitCandidates(cell: String): List<String> {
        return cell
            .split(';', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun buildSingleMap(
        dictionary: Map<String, List<String>>,
        unitToChars: Map<String, List<String>>,
    ): Map<String, List<String>> {
        val result = LinkedHashMap<String, LinkedHashSet<String>>()
        for ((key, values) in unitToChars) {
            val bucket = result.getOrPut(key) { LinkedHashSet() }
            values.filter { it.codePointCount() == 1 }.forEach(bucket::add)
        }
        for ((key, values) in dictionary) {
            val bucket = result.getOrPut(key) { LinkedHashSet() }
            values.filter { it.codePointCount() == 1 }.forEach(bucket::add)
        }
        return result.mapValues { it.value.toList() }
    }

    private fun deriveUnitToChars(dictionary: Map<String, List<String>>): Map<String, List<String>> {
        return dictionary.mapValues { entry ->
            entry.value.filter { it.codePointCount() == 1 }.distinct()
        }.filterValues { it.isNotEmpty() }
    }

    private fun deriveCharToUnit(unitToChars: Map<String, List<String>>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((unit, chars) in unitToChars) {
            for (char in chars) {
                result.putIfAbsent(char, unit)
            }
        }
        return result
    }

    private fun firstExistingAsset(names: List<String>): String? {
        for (name in names) {
            try {
                context.assets.open(name).close()
                return name
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun normalizeKey(value: String, kind: DictionaryKind): String {
        val trimmed = value.trim()
        return when (kind) {
            DictionaryKind.HANGUL -> trimmed.replace(" ", "")
            DictionaryKind.PINYIN -> trimmed.lowercase(Locale.ROOT)
                .replace("ü", "v")
                .replace("ǖ", "v")
                .replace("ǘ", "v")
                .replace("ǚ", "v")
                .replace("ǜ", "v")
                .replace("'", "")
                .replace(" ", "")
        }
    }

    private fun firstNonZero(vararg values: Int?): Int {
        return values.firstOrNull { (it ?: 0) > 0 } ?: 0
    }

    data class SourceBundle(
        val dictionary: Map<String, List<String>>,
        val singleMap: Map<String, List<String>>,
        val vocab: VocabSpec,
        val predictor: TorchPredictor?,
    )

    data class VocabSpec(
        val inputTokens: Map<String, Int>,
        val outputTokens: List<String>,
        val legalUnits: Set<String>,
        val unitToChars: Map<String, List<String>>,
        val charToUnit: Map<String, String>,
        val tokenLen: Int,
        val stride: Int,
        val maxInferenceInput: Int,
        val padId: Int,
        val unkId: Int,
        val outputSkipIds: Set<Int>,
    )

    private enum class DictionaryKind { HANGUL, PINYIN }
    private enum class Mode { HANGUL, PINYIN }
}

private fun JSONObject?.toStringIntMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        out[key] = optInt(key)
    }
    return out
}

private fun JSONObject?.toStringStringMap(): Map<String, String>? {
    if (this == null) return null
    val out = LinkedHashMap<String, String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        out[key] = optString(key)
    }
    return out
}

private fun JSONObject?.toStringListMap(): Map<String, List<String>>? {
    if (this == null) return null
    val out = LinkedHashMap<String, List<String>>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        val value = opt(key)
        when (value) {
            is JSONArray -> out[key] = value.toStringList()
            is JSONObject -> out[key] = value.toOrderedListByValue()
            is String -> out[key] = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    return out
}

private fun JSONArray?.toStringList(): List<String> = buildList {
    if (this@toStringList == null) return@buildList
    for (index in 0 until this@toStringList.length()) {
        add(this@toStringList.optString(index))
    }
}

private fun JSONArray?.toUnitCharsMap(unitKey: String): Map<String, List<String>>? {
    if (this == null) return null
    val out = LinkedHashMap<String, List<String>>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val unit = item.optString(unitKey).trim()
        if (unit.isEmpty()) continue
        val chars = item.optJSONArray("hanzi")?.toStringList().orEmpty().filter { it.isNotBlank() }
        if (chars.isNotEmpty()) {
            out[unit] = chars
        }
    }
    return out
}

private fun JSONObject.toOrderedListByValue(): List<String> {
    return keys().asSequence().map { key -> key to optInt(key) }
        .sortedBy { it.second }
        .map { it.first }
        .toList()
}
