package com.smitnk.flyagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * A specialist assistant ("Dot", after OpenDots): its own name, role instructions and permitted tool groups.
 * Tool groups keep permissions understandable: the user ticks "Web" rather than individual tools.
 */
data class Dot(val id: String, val name: String, val instructions: String, val toolGroups: Set<String>) {
    fun allows(tool: String) = GROUPS.entries.any { (g, tools) -> g in toolGroups && tool in tools }
    fun json() = JSONObject().put("id", id).put("name", name).put("instructions", instructions).put("tools", JSONArray(toolGroups.toList()))

    companion object {
        val GROUPS = linkedMapOf(
            "Web" to setOf("web_search", "fetch_page"),
            "Files" to setOf("write_file", "read_file", "list_files"),
            "Memory" to setOf("recall"),
            "Fly brain" to setOf("brain_sim"),
            "Pages" to setOf("save_page"),
            "Connectors" to setOf("mcp"),
        )
        val DEFAULT = Dot("flyagent", "FlyAgent", "", GROUPS.keys)
        val PRESETS = listOf(
            DEFAULT,
            Dot("researcher", "Researcher", "You research topics thoroughly: search the web, read sources, compare them, and cite URLs. Prefer facts over opinions.", setOf("Web", "Memory", "Pages")),
            Dot("writer", "Writer", "You turn notes and findings into clear, well-structured drafts. Ask for the audience if unclear. Offer to save drafts as pages.", setOf("Memory", "Pages", "Files")),
            Dot("neuro", "Neuro Lab", "You run and explain fruit-fly connectome simulations. Always separate real connectivity data from modeled activity.", setOf("Fly brain", "Memory", "Pages")),
        )
        fun from(o: JSONObject) = Dot(o.getString("id"), o.getString("name"), o.optString("instructions"),
            o.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: GROUPS.keys)
    }
}

/** Builds the cloud system prompt for a Dot: only the tools it may use, plus connected MCP tools. */
object Prompts {
    private val TOOL_LINES = linkedMapOf(
        "web_search" to """{"tool":"web_search","args":{"query":"..."}}""",
        "fetch_page" to """{"tool":"fetch_page","args":{"url":"https://..."}}""",
        "write_file" to """{"tool":"write_file","args":{"name":"notes.md","content":"..."}}""",
        "read_file" to """{"tool":"read_file","args":{"name":"..."}}""",
        "list_files" to """{"tool":"list_files","args":{}}""",
        "recall" to """{"tool":"recall","args":{"query":"..."}}""",
        "brain_sim" to """{"tool":"brain_sim","args":{"stimulate":"sugar","hz":100,"silence":[]}}  (fruit-fly connectome model; "stimulate" may be a list of FlyWire IDs)""",
        "save_page" to """{"tool":"save_page","args":{"title":"...","content":"markdown"}}  (saves a page to the user's Space; the user must approve it)""",
    )

    fun system(dot: Dot, mcp: List<McpTool>) = buildString {
        appendLine("You are ${dot.name}, an assistant on the user's Android phone (FlyAgent app). Work step by step using tools.")
        if (dot.instructions.isNotBlank()) appendLine("Your role: ${dot.instructions}")
        appendLine("Reply with ONLY one JSON object per turn:")
        TOOL_LINES.filterKeys(dot::allows).values.forEach(::appendLine)
        if (dot.allows("mcp") && mcp.isNotEmpty()) {
            appendLine("""Connected tools — call as {"tool":"mcp","args":{"server":"<server>","name":"<tool>","arguments":{...}}}:""")
            mcp.take(40).forEach { appendLine("- ${it.server}/${it.name}: ${it.description.take(160)} args=${it.schema.optJSONObject("properties")?.keys()?.asSequence()?.toList() ?: emptyList<String>()}${if (it.readOnly) "" else " (needs user approval)"}") }
        }
        appendLine("""{"final":"your answer, citing source URLs you actually found"}""")
        appendLine("For greetings or normal conversation, reply directly with {\"final\":\"...\"} — use tools only when needed. You may use Markdown in the final answer.")
        appendLine("If an image or file is attached, read it directly.")
        if (dot.allows("brain_sim")) appendLine("When you report brain_sim results, say the connectivity is real FlyWire data but the activity is a model simulation.")
        append(Safety.PROMPT_RULES)
    }
}
