package com.smitnk.flyagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 120000
    c.setRequestProperty("Content-Type", "application/json")
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    c.outputStream.use { it.write(body.toString().toByteArray()) }
    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(300)}")
    return JSONObject(c.inputStream.bufferedReader().readText())
}

/** OpenAI-compatible chat API: OpenAI, NVIDIA NIM, OpenRouter, Groq, Ollama… */
class OpenAiLlm(override val name: String, private val baseUrl: String, private val key: String, private val model: String) : Llm {
    override fun chat(system: String, msgs: List<Pair<String, String>>, image: Img?): String {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        msgs.forEachIndexed { i, (role, text) ->
            val content: Any = if (i == 0 && image != null) JSONArray()
                .put(JSONObject().put("type", "text").put("text", text))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${image.mime};base64,${image.base64}")))
            else text
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val r = post(baseUrl.trimEnd('/') + "/chat/completions", if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key"),
            JSONObject().put("model", model).put("messages", arr).put("temperature", 0.2).put("max_tokens", 2000))
        return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}

/** Anthropic Messages API (Claude). */
class ClaudeLlm(private val key: String, private val model: String) : Llm {
    override val name = "Claude"
    override fun chat(system: String, msgs: List<Pair<String, String>>, image: Img?): String {
        val arr = JSONArray()
        msgs.forEachIndexed { i, (role, text) ->
            val content = JSONArray()
            if (i == 0 && image != null) content.put(JSONObject().put("type", "image")
                .put("source", JSONObject().put("type", "base64").put("media_type", image.mime).put("data", image.base64)))
            content.put(JSONObject().put("type", "text").put("text", text))
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val r = post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            JSONObject().put("model", model).put("max_tokens", 2000).put("system", system).put("messages", arr))
        return r.getJSONArray("content").getJSONObject(0).getString("text")
    }
}

data class Provider(val id: String, val label: String, val defaultUrl: String, val defaultModel: String)

/** All settings live in app-private prefs; secrets are Keystore-encrypted and never enter the repo or APK. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val providers = listOf(
        Provider("claude", "Claude (Anthropic)", "https://api.anthropic.com", "claude-haiku-4-5"),
        Provider("openai", "ChatGPT (OpenAI)", "https://api.openai.com/v1", "gpt-4o-mini"),
        Provider("gemini", "Gemini (Google)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        Provider("nvidia", "NVIDIA NIM (Nemotron…)", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct"),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Provider("kimi", "Kimi (Moonshot)", "https://api.moonshot.ai/v1", "moonshot-v1-8k"),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"),
        Provider("xai", "Grok (xAI)", "https://api.x.ai/v1", "grok-3-mini"),
        Provider("custom", "OpenRouter / custom", "https://openrouter.ai/api/v1", "anthropic/claude-haiku-4.5"),
        Provider("ollama", "Ollama (your PC on Wi-Fi)", "http://192.168.1.10:11434/v1", "llama3.2"),
    )
    /** Providers that don't need a key: local servers on the user's own network. */
    private fun keyless(pr: Provider) = pr.id == "ollama" || url(pr).contains("localhost")
    fun enabled(id: String) = p.getBoolean("$id.on", false)
    fun key(id: String) = secret("$id.key")
    fun url(pr: Provider) = p.getString("${pr.id}.url", pr.defaultUrl)!!
    fun model(pr: Provider) = p.getString("${pr.id}.model", pr.defaultModel)!!
    fun save(id: String, on: Boolean, key: String, url: String, model: String) {
        p.edit().putBoolean("$id.on", on).putString("$id.url", url).putString("$id.model", model).apply(); setSecret("$id.key", key)
    }

    /** Reads a Keystore-encrypted secret; plain values from older versions are encrypted on first read. */
    fun secret(name: String): String {
        val stored = p.getString(name, "")!!
        if (stored.isNotEmpty() && !Crypto.isEncrypted(stored)) setSecret(name, stored)
        return runCatching { Crypto.decrypt(stored) }.getOrDefault("")
    }
    fun setSecret(name: String, value: String) = p.edit().putString(name, Crypto.encrypt(value)).apply()
    var githubToken: String get() = secret("github.token"); set(v) = setSecret("github.token", v)
    var speakReplies: Boolean get() = p.getBoolean("tts", false); set(v) = p.edit().putBoolean("tts", v).apply()

    var mode: Mode get() = Mode.valueOf(p.getString("mode", "HYBRID")!!); set(v) = p.edit().putString("mode", v.name).apply()
    val controller: Controller get() = ConventionalController

    // Specialist Dots: presets + user-made ones
    fun dots(): List<Dot> = Dot.PRESETS + (p.getString("dots", "[]")!!.let { org.json.JSONArray(it) }.let { a -> (0 until a.length()).map { Dot.from(a.getJSONObject(it)) } })
    fun saveCustomDots(list: List<Dot>) = p.edit().putString("dots", org.json.JSONArray(list.map { it.json() }).toString()).apply()
    var dotId: String get() = p.getString("dot", Dot.DEFAULT.id)!!; set(v) = p.edit().putString("dot", v).apply()
    val dot: Dot get() = dots().firstOrNull { it.id == dotId } ?: Dot.DEFAULT

    // MCP connectors (tokens stay in app-private prefs)
    fun connectors(): List<Connector> = org.json.JSONArray(p.getString("connectors", "[]")).let { a ->
        (0 until a.length()).map { Connector.from(a.getJSONObject(it)).let { c -> c.copy(token = runCatching { Crypto.decrypt(c.token) }.getOrDefault("")) } } }
    fun saveConnectors(list: List<Connector>) = p.edit().putString("connectors", org.json.JSONArray(list.map { it.copy(token = Crypto.encrypt(it.token)).json() }).toString()).apply()

    /** Enabled providers in priority order; the agent fails over down the list. */
    fun llms(): List<Llm> = providers.filter { enabled(it.id) && (key(it.id).isNotBlank() || keyless(it)) }.map {
        if (it.id == "claude") ClaudeLlm(key(it.id), model(it)) else OpenAiLlm(it.label, url(it), key(it.id), model(it))
    }
}
