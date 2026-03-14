package com.hanpin.ime

import android.content.Context
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream

class TorchPredictor(
    private val context: Context,
    private val assetName: String,
    private val vocab: ModelRunner.VocabSpec,
) {
    @Volatile
    private var module: Module? = null

    fun predict(units: List<String>, maxResults: Int = 10): List<String> {
        if (units.isEmpty()) return emptyList()
        if (vocab.inputTokens.isEmpty() || vocab.outputTokens.isEmpty()) return emptyList()
        val clean = units.take(vocab.maxInferenceInput)
        val module = loadModule() ?: return emptyList()
        val merged = runWindows(module, clean) ?: return emptyList()
        return beamDecode(merged, maxResults)
    }

    private fun loadModule(): Module? {
        module?.let { return it }
        return synchronized(this) {
            module?.let { return@synchronized it }
            try {
                val file = assetFile(assetName)
                val loaded = LiteModuleLoader.load(file.absolutePath)
                module = loaded
                loaded
            } catch (throwable: Throwable) {
                Logger.error("Failed to load Lite Interpreter model: $assetName", throwable)
                null
            }
        }
    }

    private fun runWindows(module: Module, units: List<String>): Array<FloatArray>? {
        val windows = makeWindows(units)
        if (windows.isEmpty()) return null
        val vocabSize = vocab.outputTokens.size
        val aggregated = Array(units.size) { FloatArray(vocabSize) }
        val counts = IntArray(units.size)

        for (window in windows) {
            val inputIds = encode(window.units)
            val tensor = Tensor.fromBlob(inputIds, longArrayOf(1, vocab.tokenLen.toLong()))
            val output = module.forward(IValue.from(tensor)).toTensor()
            val shape = output.shape()
            val data = output.dataAsFloatArray
            if (shape.size < 3) {
                Logger.error("Unexpected tensor rank ${shape.size} for $assetName")
                return null
            }
            val time = shape[1].toInt()
            val classes = shape[2].toInt()
            val useful = minOf(window.units.size, time)
            for (t in 0 until useful) {
                val target = window.start + t
                if (target !in aggregated.indices) continue
                counts[target] += 1
                val base = t * classes
                for (c in 0 until minOf(classes, vocabSize)) {
                    aggregated[target][c] += data[base + c]
                }
            }
        }

        for (index in aggregated.indices) {
            val divisor = counts[index].coerceAtLeast(1).toFloat()
            for (c in aggregated[index].indices) {
                aggregated[index][c] /= divisor
            }
        }
        return aggregated
    }

    private fun beamDecode(logits: Array<FloatArray>, maxResults: Int): List<String> {
        data class Beam(val text: String, val score: Float)
        var beams = listOf(Beam("", 0f))
        for (position in logits.indices) {
            val topIds = topK(logits[position], 10)
            val next = LinkedHashMap<String, Beam>()
            for (beam in beams) {
                for (id in topIds) {
                    if (id in vocab.outputSkipIds) continue
                    val token = vocab.outputTokens.getOrNull(id).orEmpty()
                    if (token.isBlank() || token.startsWith("<")) continue
                    val text = beam.text + token
                    val score = beam.score + logits[position][id]
                    val current = next[text]
                    if (current == null || score > current.score) {
                        next[text] = Beam(text, score)
                    }
                }
            }
            beams = next.values
                .sortedByDescending { it.score }
                .take(40)
            if (beams.isEmpty()) break
        }
        return beams.map { it.text }.distinct().take(maxResults)
    }

    private fun topK(values: FloatArray, limit: Int): List<Int> {
        return values.indices
            .sortedByDescending { values[it] }
            .take(limit)
    }

    private fun encode(units: List<String>): LongArray {
        val ids = LongArray(vocab.tokenLen) { vocab.padId.toLong() }
        units.take(vocab.tokenLen).forEachIndexed { index, token ->
            ids[index] = (vocab.inputTokens[token] ?: vocab.unkId).toLong()
        }
        return ids
    }

    private fun makeWindows(units: List<String>): List<Window> {
        if (units.isEmpty()) return emptyList()
        if (units.size <= vocab.tokenLen) return listOf(Window(0, units))

        val windows = mutableListOf<Window>()
        var start = 0
        while (start < units.size) {
            val end = minOf(units.size, start + vocab.tokenLen)
            windows += Window(start, units.subList(start, end))
            if (end == units.size) break
            start += vocab.stride
        }
        val lastStart = (units.size - vocab.tokenLen).coerceAtLeast(0)
        if (windows.lastOrNull()?.start != lastStart) {
            windows += Window(lastStart, units.subList(lastStart, units.size))
        }
        return windows
    }

    private fun assetFile(name: String): File {
        val target = File(context.filesDir, name)
        if (target.exists() && target.length() > 0) return target
        context.assets.open(name).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    private data class Window(val start: Int, val units: List<String>)
}
