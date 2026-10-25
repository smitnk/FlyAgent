package com.smitnk.flyagent

import org.json.JSONObject

class FakeStore : Store {
    val eps = mutableListOf<Episode>(); val factList = mutableListOf<String>(); val skillList = mutableListOf<Skill>()
    override fun addEpisode(task: String, steps: String, outcome: String, success: Boolean, controller: String): Long {
        val id = eps.size + 1L; eps += Episode(id, 0, task, steps, outcome, success, controller); return id
    }
    override fun episode(id: Long) = eps.firstOrNull { it.id == id }
    override fun recent(n: Int) = eps.reversed().take(n)
    override fun addFact(content: String, source: String, confidence: Double): Long { factList += content; return factList.size.toLong() }
    override fun facts() = factList.reversed()
    override fun saveSkill(name: String, trigger: String, steps: String, status: String): Skill {
        val v = skillList.filter { it.name == name }.maxOfOrNull { it.version } ?: 0
        return Skill(skillList.size + 1L, name, trigger, steps, v + 1, status).also { skillList += it }
    }
    override fun skills() = skillList.groupBy { it.name }.map { (_, l) -> l.maxBy { it.version } }
}

class FakeLlm(override val name: String = "fake", private val replies: MutableList<String> = mutableListOf(), private val fail: Boolean = false) : Llm {
    var calls = 0; var lastImage: Img? = null; var lastMsgs: List<Pair<String, String>> = emptyList()
    override fun chat(system: String, msgs: List<Pair<String, String>>, image: Img?): String {
        calls++; lastImage = image; lastMsgs = msgs
        if (fail) error("network down")
        return replies.removeFirstOrNull() ?: "{\"final\":\"done\"}"
    }
}

class FakeTools { val calls = mutableListOf<String>()
    fun run(n: String, a: JSONObject): String { calls += n; return if (n == "web_search") "- Result\n  https://example.com" else "ok:$n" } }
