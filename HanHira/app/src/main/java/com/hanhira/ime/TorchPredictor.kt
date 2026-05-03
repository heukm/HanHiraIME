package com.hanhira.ime

import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import kotlin.math.min

class TorchPredictor(
    modelFile: File,
    private val inputTokenToId: Map<String, Int>,
    private val idToLabelToken: List<String>,
    private val charToKor: Map<String, String>
) {
    private val module: Module = LiteModuleLoader.load(modelFile.absolutePath)
    private val padId = 0L
    private val unkId = 1L
    private val maxLen = 9

    fun predictCandidates(contextTokens: List<String>, externalTokens: List<String>, maxCandidates: Int = 10): List<String> {
        if (externalTokens.isEmpty()) return emptyList()
        val fixedContext = contextTokens.takeLast(maxLen)
        val outputLen = min(externalTokens.size, fixedContext.size)
        if (outputLen <= 0) return emptyList()
        val start = fixedContext.size - outputLen
        val ids = LongArray(maxLen) { padId }
        val mask = LongArray(maxLen) { 0L }
        for (i in fixedContext.indices) {
            ids[i] = (inputTokenToId[fixedContext[i]] ?: unkId.toInt()).toLong()
            mask[i] = 1L
        }
        val inputTensor = Tensor.fromBlob(ids, longArrayOf(1L, maxLen.toLong()))
        val maskTensor = Tensor.fromBlob(mask, longArrayOf(1L, maxLen.toLong()))
        return try {
            val logitsTensor = runLogits(inputTensor, maskTensor)
            beamSearch(logitsTensor.dataAsFloatArray, start, outputLen, externalTokens.takeLast(outputLen), maxCandidates)
        } catch (t: Throwable) {
            Logger.w("model", "logits method failed; falling back to argmax forward", t)
            runArgmax(inputTensor, maskTensor, start, outputLen)
        }
    }

    private fun runLogits(inputTensor: Tensor, maskTensor: Tensor): Tensor {
        return module.runMethod("logits", IValue.from(inputTensor), IValue.from(maskTensor)).toTensor()
    }

    private fun runArgmax(inputTensor: Tensor, maskTensor: Tensor, start: Int, outputLen: Int): List<String> {
        val out = module.forward(IValue.from(inputTensor), IValue.from(maskTensor)).toTensor()
        val pred = out.dataAsLongArray
        val sb = StringBuilder()
        for (i in start until start + outputLen) {
            val id = pred.getOrNull(i)?.toInt() ?: continue
            if (id <= 1 || id >= idToLabelToken.size) continue
            sb.append(idToLabelToken[id])
        }
        return if (sb.isNotEmpty()) listOf(sb.toString()) else emptyList()
    }

    private fun beamSearch(
        logits: FloatArray,
        start: Int,
        outputLen: Int,
        externalTokens: List<String>,
        maxCandidates: Int
    ): List<String> {
        val labelSize = idToLabelToken.size
        var beams = listOf(Beam("", 0.0f))
        for (posOffset in 0 until outputLen) {
            val pos = start + posOffset
            val expectedKor = externalTokens[posOffset]
            val top = topKAtPosition(logits, pos, labelSize, 12)
            val next = ArrayList<Beam>(beams.size * top.size)
            for (beam in beams) {
                for ((id, score) in top) {
                    if (id <= 1 || id >= idToLabelToken.size) continue
                    val token = idToLabelToken[id]
                    if (token.isEmpty()) continue
                    val mapped = charToKor[token]
                    if (mapped != null && mapped != expectedKor) continue
                    next += Beam(beam.text + token, beam.score + score)
                }
            }
            beams = next.sortedByDescending { it.score }.take(32)
            if (beams.isEmpty()) break
        }
        return beams
            .sortedByDescending { it.score }
            .map { it.text }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(maxCandidates)
    }

    private fun topKAtPosition(logits: FloatArray, pos: Int, labelSize: Int, k: Int): List<Pair<Int, Float>> {
        val base = pos * labelSize
        val result = ArrayList<Pair<Int, Float>>(k)
        for (id in 0 until labelSize) {
            val value = logits.getOrNull(base + id) ?: continue
            if (result.size < k) {
                result += id to value
                result.sortBy { it.second }
            } else if (value > result[0].second) {
                result[0] = id to value
                result.sortBy { it.second }
            }
        }
        return result.sortedByDescending { it.second }
    }

    private data class Beam(val text: String, val score: Float)
}
