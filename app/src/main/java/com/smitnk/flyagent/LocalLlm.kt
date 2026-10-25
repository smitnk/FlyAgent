package com.smitnk.flyagent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/** Fully offline model via Google MediaPipe LLM Inference (.task files, e.g. Gemma3-1B-IT int4 from litert-community on Hugging Face). */
class LocalLlm private constructor(private val engine: LlmInference, private val gemma: Boolean) : Llm {
    override val name = "On-device model"
    override fun chat(system: String, msgs: List<Pair<String, String>>, image: Img?): String {
        val p = if (gemma) buildString { // Gemma has no system role: fold it into the first user turn
            msgs.forEachIndexed { i, (r, t) ->
                append("<start_of_turn>${if (r == "user") "user" else "model"}\n${if (i == 0) "$system\n\n" else ""}$t<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        } else buildString { // ChatML (Qwen, Phi, SmolLM…)
            append("<|im_start|>system\n$system<|im_end|>\n")
            msgs.forEach { (r, t) -> append("<|im_start|>$r\n$t<|im_end|>\n") }
            append("<|im_start|>assistant\n")
        }
        return engine.generateResponse(p).substringBefore("<end_of_turn>").substringBefore("<|im_end|>").trim()
    }

    companion object {
        @Volatile private var cached: LocalLlm? = null
        fun file(ctx: Context) = File(ctx.filesDir, "model.task")
        /** Loads lazily (takes a few seconds); null when no model file is installed. */
        fun get(ctx: Context): LocalLlm? = cached ?: synchronized(this) {
            cached ?: file(ctx).takeIf { it.exists() && it.length() > 1_000_000 }?.let {
                val opts = LlmInference.LlmInferenceOptions.builder().setModelPath(it.absolutePath).setMaxTokens(1024).build()
                LocalLlm(LlmInference.createFromOptions(ctx, opts), ctx.getSharedPreferences("settings", 0).getBoolean("model.gemma", true)).also { m -> cached = m }
            }
        }
        fun reset() { cached = null }
    }
}
