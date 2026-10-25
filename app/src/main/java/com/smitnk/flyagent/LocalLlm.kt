package com.smitnk.flyagent

import android.content.Context
import java.io.File

/**
 * Fully offline language model: Qwen3-0.6B (Apache-2.0) in GGUF format, run by llama.cpp (MIT) through JNI.
 * Weights are downloaded once (not bundled in the APK) and memory-mapped.
 */
class LocalLlm private constructor(private val h: Long, private val cpu: String) : Llm {
    override val name = "Qwen3-0.6B (on device)"
    override val lastStats get() = "${NativeLlm.stats(h)} | $cpu"
    override fun chat(system: String, msgs: List<Pair<String, String>>, image: Img?) = chatStream(system, msgs, 384) { true }

    override fun chatStream(system: String, msgs: List<Pair<String, String>>, maxTokens: Int, onToken: (String) -> Boolean): String {
        val roles = arrayOf("system") + msgs.map { it.first }
        // "/no_think" is Qwen3's switch for direct answers without a long reasoning trace (much faster on phones)
        val texts = arrayOf(system) + msgs.mapIndexed { i, (_, t) -> if (i == msgs.lastIndex) "$t /no_think" else t }
        val raw = StringBuilder()
        val out = NativeLlm.chat(h, roles, texts, maxTokens) { b ->
            raw.append(String(b, Charsets.UTF_8))
            val visible = stripThink(raw.toString())
            onToken(visible) // full visible text so far
        }
        return stripThink(String(out, Charsets.UTF_8))
    }

    companion object {
        fun stripThink(s: String) = s.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").trim()

        data class ModelOption(val label: String, val file: String, val sizeMb: Int)
        const val REPO = "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/main/"
        val MODELS = listOf(
            ModelOption("Qwen3-0.6B Q4_0 (recommended, faster)", "Qwen3-0.6B-Q4_0.gguf", 429),
            ModelOption("Qwen3-0.6B Q8_0 (higher quality)", "Qwen3-0.6B-Q8_0.gguf", 805),
        )

        // internal storage: memory-mapping a file on Android's FUSE-backed shared storage can stall on every token
        fun file(ctx: Context) = File(ctx.filesDir, "model.gguf")
        private fun legacyFile(ctx: Context) = ctx.getExternalFilesDir(null)?.let { File(it, "model.gguf") }
        @Volatile private var cached: LocalLlm? = null
        private var handle = 0L

        /** Loads lazily (a few seconds); null when no model is installed. */
        @Synchronized fun get(ctx: Context): LocalLlm? {
            cached?.let { return it }
            legacyFile(ctx)?.takeIf { it.exists() && !file(ctx).exists() }?.let { old -> // one-time move from v0.4 location
                val tmp = File(ctx.filesDir, "model.moving")
                old.inputStream().use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
                if (tmp.length() == old.length() && tmp.renameTo(file(ctx))) old.delete() else tmp.delete()
            }
            val f = file(ctx).takeIf { it.exists() && it.length() > 50_000_000 } ?: return null
            val cpu = NativeLlm.loadBackends(ctx.applicationInfo.nativeLibraryDir)
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4) // leave cores for the UI, limit heat
            handle = NativeLlm.load(f.absolutePath, 2048, threads)
            return LocalLlm(handle, cpu).also { cached = it }
        }
        /** Frees the model's RAM (called when the app goes to background or the model changes). */
        @Synchronized fun reset() { if (handle != 0L) NativeLlm.free(handle); handle = 0; cached = null }
    }
}
