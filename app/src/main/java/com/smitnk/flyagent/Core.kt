package com.smitnk.flyagent

import org.json.JSONArray
import org.json.JSONObject

data class Episode(val id: Long, val ts: Long, val task: String, val steps: String, val outcome: String, val success: Boolean, val controller: String = "")
data class Skill(val id: Long, val name: String, val trigger: String, val steps: String, val version: Int, val status: String)
data class Img(val base64: String, val mime: String, val bytes: ByteArray)

enum class Mode { OFFLINE, CLOUD, HYBRID }

/** Persistent memory interface (SQLite on device, in-memory in tests). */
interface Store {
    fun addEpisode(task: String, steps: String, outcome: String, success: Boolean, controller: String): Long
    fun episode(id: Long): Episode?
    fun recent(n: Int): List<Episode>
    fun addFact(content: String, source: String, confidence: Double): Long
    fun facts(): List<String>
    fun saveSkill(name: String, trigger: String, steps: String, status: String): Skill
    fun skills(): List<Skill>
    fun recall(query: String, limit: Int = 3) = Recall.rank(query, recent(500), limit)
}

/** One cloud reasoning provider. */
interface Llm {
    val name: String
    fun chat(system: String, msgs: List<Pair<String, String>>, image: Img?): String
}

object Recall {
    fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    // shortcut: keyword overlap; upgrade to FTS/embeddings in Milestone 4
    fun rank(query: String, eps: List<Episode>, limit: Int): List<Episode> {
        val q = words(query)
        return eps.map { it to (words(it.task + " " + it.outcome) intersect q).size }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
    fun bestSkill(task: String, skills: List<Skill>): Skill? {
        val q = words(task)
        return skills.filter { it.status == "reliable" }
            .map { it to (words(it.trigger) intersect q).size.toDouble() / maxOf(1, words(it.trigger).size) }
            .filter { it.second >= 0.6 }.maxByOrNull { it.second }?.first
    }
}

/** Parses the model's JSON action; tolerant of prose or ```fences around it. */
object ActionParser {
    data class Action(val tool: String?, val args: JSONObject, val answer: String?)
    fun parse(text: String): Action? {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            val o = JSONObject(text.substring(s, e + 1))
            when {
                o.has("final") -> Action(null, JSONObject(), o.getString("final"))
                o.has("tool") -> Action(o.getString("tool"), o.optJSONObject("args") ?: JSONObject(), null)
                else -> null
            }
        } catch (_: Exception) { null }
    }
}

/** Offline rule-based commands (no model needed). */
object LocalCommands {
    fun parse(task: String): Pair<String, JSONObject>? {
        val t = task.trim()
        return when {
            t.startsWith("search ", true) -> "web_search" to JSONObject().put("query", t.drop(7).trim())
            t.startsWith("fetch ", true) -> "fetch_page" to JSONObject().put("url", t.drop(6).trim())
            t.startsWith("write ", true) && ':' in t -> "write_file" to JSONObject()
                .put("name", t.drop(6).substringBefore(':').trim()).put("content", t.substringAfter(':').trim())
            t.startsWith("read ", true) -> "read_file" to JSONObject().put("name", t.drop(5).trim())
            t.equals("files", true) -> "list_files" to JSONObject()
            t.startsWith("recall ", true) -> "recall" to JSONObject().put("query", t.drop(7).trim())
            t.equals("brain", true) || t.startsWith("brain ", true) -> "brain_sim" to brainArgs(t.drop(5).trim())
            else -> null
        }
    }
    /** "brain" | "brain info" | "brain sugar [hz]" | "brain stim <id,id> [hz]" | "brain silence <id> [hz]" (silencing during sugar stimulation) */
    fun brainArgs(rest: String): JSONObject {
        val w = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hz = w.drop(1).lastOrNull()?.toDoubleOrNull()?.takeIf { it <= 400 }
        val a = JSONObject()
        when (w.firstOrNull()?.lowercase()) {
            null, "info" -> a.put("action", "info")
            "stim", "stimulate" -> a.put("stimulate", w.drop(1).filter { it.toDoubleOrNull() != hz || hz == null }.joinToString(","))
            "silence" -> a.put("stimulate", "sugar").put("silence", org.json.JSONArray(w.drop(1).mapNotNull { it.toLongOrNull() }.filter { it > 1_000_000 }))
            else -> a.put("stimulate", "sugar")
        }
        hz?.let { a.put("hz", it) }
        return a
    }
    const val HELP = "Offline commands:\nsearch <query>\nfetch <url>\nwrite <name.txt>: <content>\nread <name>\nfiles\nrecall <words>\nbrain | brain sugar [hz] | brain stim <flywire ids> [hz] | brain silence <id>\n(attach an image to read its text offline)"
}

/** Skill steps are a JSON array of {tool,args}; string args equal to the original input become {input}. */
object SkillTemplate {
    fun make(steps: List<Pair<String, JSONObject>>, input: String): String = JSONArray().apply {
        steps.forEach { (tool, args) ->
            val a = JSONObject()
            args.keys().forEach { k -> val v = args.get(k); a.put(k, if (v is String && input.isNotBlank() && v.trim() == input.trim()) "{input}" else v) }
            put(JSONObject().put("tool", tool).put("args", a))
        }
    }.toString()
    fun expand(steps: String, input: String): List<Pair<String, JSONObject>> {
        val arr = JSONArray(steps)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val a = JSONObject(o.getJSONObject("args").toString())
            a.keys().asSequence().toList().forEach { k -> (a.get(k) as? String)?.let { a.put(k, it.replace("{input}", input)) } }
            o.getString("tool") to a
        }
    }
    /** The variable part of a command-style task, e.g. "search cats" -> "cats". */
    fun inputOf(task: String) = LocalCommands.parse(task)?.second?.let { a -> a.keys().asSequence().map { a.get(it) }.filterIsInstance<String>().firstOrNull() } ?: task
}
