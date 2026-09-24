package com.jarvis.app

import android.content.Context
import android.net.Uri
import java.io.File

class LocalModel(private val context: Context) {
    val modelFile: File get() {
    val dir = File(context.filesDir, "jarvis_models")
    val foundFile = dir.listFiles()?.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }
    return foundFile ?: File(dir, "default.gguf")
}

    val installed: Boolean get() = modelFile.exists() && modelFile.length() > 64

    fun importUri(uri: Uri): String {
        val dir = modelFile.parentFile!!
        if (!dir.exists()) dir.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return "Could not open that file."
        if (modelFile.length() < 64) {
            modelFile.delete()
            return "File is too small to be a model."
        }
        val magic = modelFile.inputStream().use { it.readNBytes(4) }
        if (String(magic) != "GGUF") {
            modelFile.delete()
            return "That file is not a GGUF model."
        }
        release()
        return "Imported ${modelFile.length() / (1024 * 1024)} MB. JARVIS can use it now."
    }

    private var handle: Any? = null

    fun ensureLoaded(): String? {
        if (handle != null) return null
        if (!installed) return "No GGUF yet. Open Settings → AI Model and import your file."
        return try {
            handle = LlamaBridge.load(modelFile)
            null
        } catch (t: Throwable) {
            "Model load failed: ${t.cause?.message ?: t.message}"
        }
    }

    fun generate(prompt: String): String {
        ensureLoaded()?.let { return it }
        val h = handle ?: return "Model is not loaded."
        return try {
            LlamaBridge.complete(h, prompt)
        } catch (t: Throwable) {
            "Model error: ${t.cause?.message ?: t.message}"
        }
    }

    fun release() {
        val h = handle ?: return
        runCatching { LlamaBridge.release(h) }
        handle = null
    }
}

private object LlamaBridge {
    private const val LLAMA = "dev.ffmpegkit.llama.Llama"
    private const val CONFIG = "dev.ffmpegkit.llama.LlamaConfig"
    private fun cls() = Class.forName(LLAMA)
    private fun inst(c: Class<*>) = runCatching { c.getField("INSTANCE").get(null) }.getOrNull()
    private fun method(c: Class<*>, name: String, n: Int) =
        c.methods.first { it.name == name && it.parameterCount == n }

    fun load(file: File): Any {
        val c = cls()
        val cfgC = Class.forName(CONFIG)
        val cfg = cfgC.constructors.first { it.parameterCount == 0 }.newInstance()
        cfgC.methods.firstOrNull { it.name.equals("setContextSize", true) && it.parameterCount == 1 }?.invoke(cfg, 1024)
        cfgC.methods.firstOrNull { it.name.equals("setThreads", true) && it.parameterCount == 1 }?.invoke(cfg, 3)
        return method(c, "loadModel", 2).invoke(inst(c), file.absolutePath, cfg)
            ?: error("loadModel returned null")
    }

    fun complete(model: Any, prompt: String): String {
        val c = cls()
        val result = method(c, "complete", 4).invoke(
            inst(c), model, prompt,
            "You are JARVIS, a concise helpful on-device assistant.", 160
        ) ?: return ""
        return result.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
            ?.invoke(result)?.toString()
            ?: result.javaClass.fields.firstOrNull { it.name == "text" }?.get(result)?.toString()
            ?: result.toString()
    }

    fun release(model: Any) {
        val c = cls()
        method(c, "releaseModel", 1).invoke(inst(c), model)
    }
}
