package com.hanhira.ime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class CandidateEngine private constructor(private val appContext: Context) {
    @Volatile private var loaded = false
    @Volatile private var loading = false
    private val executor = Executors.newSingleThreadExecutor()

    private val dictionary = linkedMapOf<String, MutableList<String>>()
    private val inputTokenToId = linkedMapOf<String, Int>()
    private val idToLabelToken = mutableListOf<String>()
    private val charToKor = linkedMapOf<String, String>()
    private val modelRunner = ModelRunner(appContext)

    fun preload() {
        if (loaded || loading) return
        loading = true
        executor.execute {
            try { ensureLoaded() } finally { loading = false }
        }
    }

    fun candidates(externalBuffer: String, internalBuffer: String): List<String> {
        if (externalBuffer.isBlank()) return emptyList()
        ensureLoaded()
        val source = normalizeInputSource(externalBuffer)
        val dict = dictionary[source].orEmpty().distinct()
        val sourceTokens = toInputTokens(source)
        val internalTokens = toInputTokens(internalBuffer)
        val context = (internalTokens + sourceTokens).takeLast(9)
        val modelCandidates = modelRunner
            .ensureLoaded(inputTokenToId, idToLabelToken, charToKor)
            ?.predictCandidates(context, sourceTokens, maxCandidates = 10)
            .orEmpty()
            .filterNot { it in dict }
            .distinct()

        val merged = if (sourceTokens.size == 1) {
            dict + modelCandidates.take(7)
        } else {
            dict + modelCandidates.take(7)
        }
        return if (sourceTokens.size == 1) merged.distinct() else merged.distinct().take(10)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            Logger.d("assets", "loading dictionaries and vocab")
            loadVocab()
            loadCombinedWorkbook()
            loadKanaWorkbook()
            loaded = true
            Logger.d("assets", "loaded dictionary=${dictionary.size}, inputVocab=${inputTokenToId.size}, labels=${idToLabelToken.size}")
        }
    }

    private fun loadVocab() {
        val asset = AssetLocator.findAsset(appContext, "kohan_vocab2", setOf("json"))
        if (asset == null) {
            Logger.w("vocab", "kohan_vocab2.json asset not found")
            return
        }
        val json = appContext.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(json)
        root.optJSONObject("kor2chars")?.let { kor2chars ->
            for (key in kor2chars.keys()) {
                val arr = kor2chars.optJSONArray(key) ?: continue
                addCandidates(key, jsonArrayToStrings(arr))
            }
        }
        root.optJSONObject("maps")?.let { maps ->
            for (mapKey in listOf("hangul_to_hanzi", "hangul_to_hiragana", "hangul_to_katakana")) {
                maps.optJSONObject(mapKey)?.let { obj ->
                    for (key in obj.keys()) {
                        obj.optJSONArray(key)?.let { arr -> addCandidates(key, jsonArrayToStrings(arr)) }
                    }
                }
            }
        }
        root.optJSONObject("char2kor")?.let { obj ->
            for (key in obj.keys()) charToKor[key] = normalizeInputSource(obj.optString(key))
        }
        root.optJSONObject("model")?.let { model ->
            model.optJSONObject("input_token_to_id")?.let { obj ->
                for (key in obj.keys()) inputTokenToId[key] = obj.optInt(key)
            }
            model.optJSONArray("id_to_label_token")?.let { arr ->
                idToLabelToken.clear()
                for (i in 0 until arr.length()) idToLabelToken += arr.optString(i)
            }
        }
    }

    private fun loadCombinedWorkbook() {
        val asset = AssetLocator.findAsset(appContext, "combined_korean_japanese2", setOf("xlsx", "csv")) ?: return
        runCatching {
            val rows = if (asset.endsWith(".csv", true)) readCsv(asset) else SimpleXlsx.readRows(appContext, asset)
            for (row in rows) {
                if (row.size < 2) continue
                val key = normalizeInputSource(row[0].trim())
                val values = splitCandidates(row[1])
                if (key.isNotEmpty()) addCandidates(key, values)
            }
        }.onFailure { Logger.w("dict", "failed to load combined dictionary", it) }
    }

    private fun loadKanaWorkbook() {
        val asset = AssetLocator.findAsset(appContext, "kana", setOf("xlsx", "csv")) ?: return
        runCatching {
            val rows = if (asset.endsWith(".csv", true)) readCsv(asset) else SimpleXlsx.readRows(appContext, asset)
            for (row in rows) {
                if (row.size < 2) continue
                val key = normalizeInputSource(row[0].trim())
                val values = splitCandidates(row[1])
                if (key.isNotEmpty()) addCandidates(key, values)
            }
        }.onFailure { Logger.w("dict", "failed to load kana dictionary", it) }
    }

    private fun readCsv(asset: String): List<List<String>> {
        return appContext.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { line -> line.split(',', ';').map { it.trim() } }.toList()
        }
    }

    private fun addCandidates(key: String, values: List<String>) {
        if (key.isEmpty()) return
        val list = dictionary.getOrPut(key) { mutableListOf() }
        for (v in values) {
            val s = v.trim()
            if (s.isNotEmpty() && s !in list) list += s
        }
    }

    private fun splitCandidates(text: String): List<String> = text
        .split(';', ',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private fun jsonArrayToStrings(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    companion object {
        @Volatile private var INSTANCE: CandidateEngine? = null
        fun get(context: Context): CandidateEngine = INSTANCE ?: synchronized(this) {
            INSTANCE ?: CandidateEngine(context.applicationContext).also { INSTANCE = it }
        }

        fun normalizeInputSource(text: String): String = buildString {
            text.codePoints().forEach { cp ->
                if (cp == 'ー'.code) append('-') else appendCodePoint(cp)
            }
        }

        fun toInputTokens(text: String): List<String> {
            val out = ArrayList<String>()
            text.codePoints().forEach { cp -> out += String(Character.toChars(cp)) }
            return out
        }
    }
}

object SimpleXlsx {
    fun readRows(context: Context, asset: String): List<List<String>> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(context.assets.open(asset)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) }.orEmpty()
        val sheetName = entries.keys.firstOrNull { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            ?: return emptyList()
        return parseSheet(entries[sheetName] ?: return emptyList(), shared)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val nodes = doc.getElementsByTagNameNS("*", "si")
        val out = ArrayList<String>(nodes.length)
        for (i in 0 until nodes.length) {
            val si = nodes.item(i) as Element
            val tNodes = si.getElementsByTagNameNS("*", "t")
            val sb = StringBuilder()
            for (j in 0 until tNodes.length) sb.append(tNodes.item(j).textContent ?: "")
            out += sb.toString()
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val rows = doc.getElementsByTagNameNS("*", "row")
        val out = ArrayList<List<String>>(rows.length)
        for (i in 0 until rows.length) {
            val row = rows.item(i) as Element
            val cells = row.getElementsByTagNameNS("*", "c")
            val values = ArrayList<String>(cells.length)
            for (j in 0 until cells.length) {
                val cell = cells.item(j) as Element
                val type = cell.getAttribute("t")
                val value = when (type) {
                    "s" -> {
                        val idx = firstText(cell, "v").toIntOrNull() ?: -1
                        shared.getOrNull(idx).orEmpty()
                    }
                    "inlineStr" -> firstText(cell, "t")
                    else -> firstText(cell, "v")
                }
                values += value
            }
            if (values.any { it.isNotBlank() }) out += values
        }
        return out
    }

    private fun firstText(element: Element, localName: String): String {
        val nodes = element.getElementsByTagNameNS("*", localName)
        return if (nodes.length > 0) nodes.item(0).textContent.orEmpty() else ""
    }
}
