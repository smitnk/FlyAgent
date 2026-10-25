package com.smitnk.flyagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A remote MCP server the user connected (Streamable HTTP transport). */
data class Connector(val name: String, val url: String, val token: String, val enabled: Boolean = true) {
    fun json() = JSONObject().put("name", name).put("url", url).put("token", token).put("enabled", enabled)
    companion object { fun from(o: JSONObject) = Connector(o.getString("name"), o.getString("url"), o.optString("token"), o.optBoolean("enabled", true)) }
}
data class McpTool(val server: String, val name: String, val description: String, val schema: JSONObject, val readOnly: Boolean)

/** Minimal MCP client (JSON-RPC 2.0 over Streamable HTTP): initialize, tools/list, tools/call. */
class McpClient(private val c: Connector, private val post: (String, Map<String, String>, String) -> Pair<Map<String, String>, String> = ::httpPost) {
    private var session: String? = null
    private var nextId = 1

    private fun rpc(method: String, params: JSONObject?, notify: Boolean = false): JSONObject? {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) body.put("params", params)
        if (!notify) body.put("id", nextId++)
        val headers = buildMap {
            put("Content-Type", "application/json"); put("Accept", "application/json, text/event-stream")
            if (c.token.isNotBlank()) put("Authorization", "Bearer ${c.token}")
            session?.let { put("Mcp-Session-Id", it) }
        }
        val (respHeaders, text) = post(c.url, headers, body.toString())
        respHeaders.entries.firstOrNull { it.key.equals("Mcp-Session-Id", true) }?.let { session = it.value }
        if (notify) return null
        val msg = parse(text) ?: error("${c.name}: empty response")
        msg.optJSONObject("error")?.let { error("${c.name}: ${it.optString("message")}") }
        return msg.getJSONObject("result")
    }

    fun connect(): List<McpTool> {
        rpc("initialize", JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "FlyAgent").put("version", "0.5")))
        rpc("notifications/initialized", null, notify = true)
        val tools = rpc("tools/list", JSONObject())!!.getJSONArray("tools")
        return (0 until tools.length()).map { i ->
            val t = tools.getJSONObject(i)
            McpTool(c.name, t.getString("name"), t.optString("description"), t.optJSONObject("inputSchema") ?: JSONObject(),
                t.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) ?: false)
        }
    }

    fun call(tool: String, args: JSONObject): String {
        if (session == null) connect()
        val r = rpc("tools/call", JSONObject().put("name", tool).put("arguments", args))!!
        val content = r.optJSONArray("content") ?: JSONArray()
        val text = (0 until content.length()).joinToString("\n") { i -> content.getJSONObject(i).let { if (it.optString("type") == "text") it.optString("text") else "[${it.optString("type")} content]" } }
        return (if (r.optBoolean("isError")) "ERROR: " else "") + text.take(8000)
    }

    companion object {
        /** Accepts a plain JSON body or an SSE stream ("data: {...}" lines); returns the JSON-RPC response. */
        fun parse(text: String): JSONObject? {
            val t = text.trim()
            if (t.startsWith("{")) return JSONObject(t)
            return t.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.lastOrNull { it.has("result") || it.has("error") }
        }

        fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Map<String, String>, String> {
            require(url.startsWith("https://") || url.startsWith("http://localhost")) { "connectors must use https" }
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 60000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code == 202) return conn.headerFields.filterKeys { it != null }.mapValues { it.value.first() } to ""
            if (code !in 200..299) error("HTTP $code: ${text.take(200)}")
            return conn.headerFields.filterKeys { it != null }.mapValues { it.value.first() } to text
        }
    }
}

/** Live MCP sessions for the enabled connectors; tool lists are fetched once per app session. */
class ConnectorRegistry(private val list: () -> List<Connector>) {
    private val clients = HashMap<String, McpClient>()
    private val tools = HashMap<String, List<McpTool>>()

    @Synchronized fun tools(): List<McpTool> = list().filter { it.enabled }.flatMap { c ->
        tools.getOrPut(c.name) { runCatching { client(c).connect() }.getOrDefault(emptyList()) }
    }
    @Synchronized fun call(server: String, tool: String, args: JSONObject): String {
        val c = list().firstOrNull { it.name == server && it.enabled } ?: return "ERROR: connector '$server' is not connected"
        return runCatching { client(c).call(tool, args) }.getOrElse { "ERROR: ${it.message}" }
    }
    @Synchronized fun test(c: Connector): List<McpTool> { clients.remove(c.name); tools.remove(c.name); return client(c).connect().also { tools[c.name] = it } }
    @Synchronized fun reset() { clients.clear(); tools.clear() }
    private fun client(c: Connector) = clients.getOrPut(c.name) { McpClient(c) }
}
