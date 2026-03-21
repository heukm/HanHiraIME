package com.hanpin.ime

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

object LabeledCandidateHighlighter {
    private const val ASSET_NAME = "korean_chinese2_labeled.json"

    private val loaded = AtomicBoolean(false)
    private val pairLabelMap = LinkedHashMap<String, Int>()
    private val candidateLabelMap = LinkedHashMap<String, Int>()

    private val redColor: Int = Color.RED
    private val blueColor: Int = Color.parseColor("#66CCFF")

    fun ensureLoaded(context: Context) {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            loadInternal(context.applicationContext)
            loaded.set(true)
        }
    }

    fun colorForCandidate(candidate: String): Int {
        // 한글자 한자는 무조건 색칠 금지
        if (isSingleHanzi(candidate)) return Color.WHITE
        return labelToColor(candidateLabelMap[candidate])
    }

    fun colorForCandidate(hangul: String, candidate: String): Int {
        // 쌍이 정확히 맞아도 한글자 한자는 무조건 색칠 금지
        if (isSingleHanzi(candidate)) return Color.WHITE

        val pairLabel = pairLabelMap[pairKey(hangul, candidate)]
        return if (pairLabel != null) {
            labelToColor(pairLabel)
        } else {
            colorForCandidate(candidate)
        }
    }

    private fun loadInternal(context: Context) {
        pairLabelMap.clear()
        candidateLabelMap.clear()

        val jsonText = context.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val root = JSONObject(jsonText)
        val labeledArray = when {
            root.has("labeled") -> root.optJSONArray("labeled")
            root.has("rows") -> root.optJSONArray("rows")
            else -> JSONArray()
        } ?: JSONArray()

        for (index in 0 until labeledArray.length()) {
            val row = labeledArray.optJSONObject(index) ?: continue
            val hangul = row.optString("한글").trim()
            val hanzi = row.optString("한자").trim()
            val label = row.optInt("레이블", 0)

            if (hangul.isEmpty() || hanzi.isEmpty()) continue

            pairLabelMap[pairKey(hangul, hanzi)] = label

            // 한글자 한자는 전역 fallback 맵에도 저장하지 않음
            if (!isSingleHanzi(hanzi)) {
                candidateLabelMap[hanzi] = mergeLabel(candidateLabelMap[hanzi], label)
            }
        }
    }

    private fun labelToColor(label: Int?): Int {
        return when (label) {
            1 -> redColor
            2, 4 -> blueColor
            else -> Color.WHITE
        }
    }

    private fun mergeLabel(existing: Int?, incoming: Int): Int {
        if (existing == null) return incoming
        return if (priority(incoming) >= priority(existing)) incoming else existing
    }

    private fun priority(label: Int): Int {
        return when (label) {
            1 -> 3
            2, 4 -> 2
            else -> 1
        }
    }

    private fun pairKey(hangul: String, hanzi: String): String {
        return hangul + '\u0001' + hanzi
    }

    private fun isSingleHanzi(text: String): Boolean {
        if (text.isBlank()) return false
        return text.codePointCount(0, text.length) == 1
    }
}