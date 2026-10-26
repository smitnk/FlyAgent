package com.smitnk.flyagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * A specialist assistant ("Dot", after OpenDots): its own name, role instructions and permitted tool groups.
 * Tool groups keep permissions understandable: the user ticks "Web" rather than individual tools.
 */
data class Dot(val id: String, val name: String, val instructions: String, val toolGroups: Set<String>, val maxSteps: Int = 8) {
    fun allows(tool: String) = GROUPS.entries.any { (g, tools) -> g in toolGroups && tool in tools }
    fun json() = JSONObject().put("id", id).put("name", name).put("instructions", instructions).put("tools", JSONArray(toolGroups.toList())).put("maxSteps", maxSteps)

    companion object {
        val GROUPS = linkedMapOf(
            "Web" to setOf("web_search", "fetch_page"),
            "Files" to setOf("write_file", "read_file", "list_files"),
            "Memory" to setOf("recall"),
            "Fly brain" to setOf("brain_sim"),
            "Pages" to setOf("save_page"),
            "Connectors" to setOf("mcp"),
            "Email" to setOf("draft_email"),
            "GitHub" to GitHub.TOOLS,
        )
        val DEFAULT = Dot("flyagent", "FlyAgent", "", GROUPS.keys)
        val PRESETS = listOf(
            DEFAULT,
            Dot("researcher", "Researcher", "Deep research mode: run several searches with different wording, open and read at least 3–5 of the best sources with fetch_page, " +
                "compare them, note disagreements, then write a structured report (summary, key findings, details, sources with URLs). Offer to save it as a page.", setOf("Web", "Memory", "Pages"), maxSteps = 16),
            Dot("coder", "Coder", "You are a careful software engineer working on the user's GitHub repositories. Explore with gh_tree/gh_search, read the relevant files, " +
                "explain bugs you find with file and line references, and propose minimal fixes. Only open a pull request (gh_pr, full new file contents) or issue when the user asks; they must approve it. " +
                "To build an app: gh_build, then gh_runs for the run id, gh_wait until it finishes; if it fails, read the failed step and fix the code; if it succeeds, gh_install puts the APK on this phone.", setOf("GitHub", "Web", "Memory", "Pages"), maxSteps = 24),
            Dot("assistant", "Mail & Tasks", "You help with everyday tasks: write clear, polite emails in the user's voice (use draft_email so they can review and send), reminders, plans and summaries.", setOf("Email", "Web", "Memory", "Pages")),
            Dot("writer", "Writer", "You turn notes and findings into clear, well-structured drafts. Ask for the audience if unclear. Offer to save drafts as pages.", setOf("Memory", "Pages", "Files")),
            Dot("neuro", "Neuro Lab", "You run and explain fruit-fly connectome simulations. Always separate real connectivity data from modeled activity.", setOf("Fly brain", "Memory", "Pages")),
        )
        fun from(o: JSONObject) = Dot(o.getString("id"), o.getString("name"), o.optString("instructions"),
            o.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: GROUPS.keys, o.optInt("maxSteps", 8))
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
        "draft_email" to """{"tool":"draft_email","args":{"to":"name@example.com","subject":"...","body":"..."}}  (user reviews, then it opens in their email app to send)""",
        "gh_repos" to """{"tool":"gh_repos","args":{}}  (the user's GitHub repositories)""",
        "gh_tree" to """{"tool":"gh_tree","args":{"repo":"owner/name","path":"src"}}""",
        "gh_read" to """{"tool":"gh_read","args":{"repo":"owner/name","path":"src/Main.kt"}}""",
        "gh_search" to """{"tool":"gh_search","args":{"repo":"owner/name","query":"functionName"}}""",
        "gh_issue" to """{"tool":"gh_issue","args":{"repo":"owner/name","title":"...","body":"..."}}  (needs approval)""",
        "gh_runs" to """{"tool":"gh_runs","args":{"repo":"owner/name"}}  (recent GitHub Actions builds)""",
        "gh_build" to """{"tool":"gh_build","args":{"repo":"owner/name","workflow":"build.yml"}}  (starts a cloud build; needs approval)""",
        "gh_wait" to """{"tool":"gh_wait","args":{"repo":"owner/name","run_id":123}}  (waits for a build and reports failed steps)""",
        "gh_install" to """{"tool":"gh_install","args":{"repo":"owner/name","run_id":123}}  (downloads the built APK and opens the installer; needs approval)""",
        "gh_pr" to """{"tool":"gh_pr","args":{"repo":"owner/name","title":"...","body":"...","files":[{"path":"src/Main.kt","content":"<full new file>"}]}}  (needs approval)""",
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
        appendLine("To show an image in the chat, put it on its own line as Markdown: ![short description](https://direct-image-url.jpg) — only use real image URLs you found; never invent URLs. Links are tappable: use [title](https://...).")
        if (dot.allows("brain_sim")) appendLine("When you report brain_sim results, say the connectivity is real FlyWire data but the activity is a model simulation.")
        append(Safety.PROMPT_RULES)
    }
}
