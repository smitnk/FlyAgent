package com.smitnk.flyagent

data class Route(val useCloud: Boolean, val recall: Boolean, val activation: Map<String, Double>, val note: String)

/** Cognitive controller: decides local vs cloud and which modules (memory recall) to run. Swappable for A/B tests. */
interface Controller {
    val name: String
    fun route(task: String, hasImage: Boolean): Route
}

/** Baseline: always recall memory; local if it's a plain command, otherwise cloud. */
object ConventionalController : Controller {
    override val name = "conventional"
    override fun route(task: String, hasImage: Boolean): Route {
        val local = LocalCommands.parse(task) != null && !hasImage
        return Route(!local, true, emptyMap(), if (local) "plain command → local" else "→ cloud")
    }
}
