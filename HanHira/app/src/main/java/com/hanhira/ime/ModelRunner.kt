package com.hanhira.ime

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class ModelRunner(private val context: Context) {
    @Volatile private var predictor: TorchPredictor? = null
    @Volatile private var loadTried = false

    fun ensureLoaded(inputTokenToId: Map<String, Int>, idToLabelToken: List<String>, charToKor: Map<String, String>): TorchPredictor? {
        predictor?.let { return it }
        synchronized(this) {
            predictor?.let { return it }
            if (loadTried) return null
            loadTried = true
            val assetName = AssetLocator.findAsset(context, "kohan_model_lite3", setOf("ptl"))
            if (assetName == null) {
                Logger.w("model", "kohan_model_lite3.ptl asset not found")
                return null
            }
            return try {
                val modelFile = copyAssetToCache(assetName)
                TorchPredictor(modelFile, inputTokenToId, idToLabelToken, charToKor).also { predictor = it }
            } catch (t: Throwable) {
                Logger.e("model", "failed to load Lite model", t)
                null
            }
        }
    }

    private fun copyAssetToCache(assetName: String): File {
        val out = File(context.filesDir, "kohan_model_lite3.ptl")
        val marker = File(context.filesDir, "kohan_model_lite3.ptl.assetname")
        if (out.exists() && marker.exists() && marker.readText() == assetName && out.length() > 0L) {
            return out
        }
        context.assets.open(assetName).use { input ->
            FileOutputStream(out, false).use { output -> input.copyTo(output) }
        }
        marker.writeText(assetName)
        return out
    }
}

object AssetLocator {
    fun findAsset(context: Context, prefix: String, extensions: Set<String>): String? {
        val all = listAssetsRecursive(context, "")
        val normalizedExt = extensions.map { it.lowercase() }.toSet()
        val exactCandidates = normalizedExt.flatMap { ext ->
            listOf("$prefix.$ext", "assetsJap/$prefix.$ext")
        }
        exactCandidates.firstOrNull { it in all }?.let { return it }
        return all.firstOrNull { path ->
            val name = path.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            name.startsWith(prefix) && ext in normalizedExt
        }
    }

    private fun listAssetsRecursive(context: Context, path: String): List<String> {
        val result = mutableListOf<String>()
        fun walk(p: String) {
            val children = try { context.assets.list(p)?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
            if (children.isEmpty()) {
                if (p.isNotEmpty()) result += p
                return
            }
            for (child in children) {
                val next = if (p.isEmpty()) child else "$p/$child"
                val grand = try { context.assets.list(next)?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
                if (grand.isEmpty()) result += next else walk(next)
            }
        }
        walk(path)
        return result
    }
}
