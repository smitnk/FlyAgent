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

/**
 * Port of bio-agi's routing idea (github.com/shepherdscientific/bio-agi, MIT):
 * input features drive 6 neuron populations (mushroom body=memory, central complex=world model, protocerebral bridge=goal,
 * SEZ=reward, lateral horn=emotion, fan-shaped body=executive) in a small leaky integrate-and-fire network;
 * spike rates become an activation vector, scaled by urgency = arousal × max(0,−valence); modules under 0.1 are skipped.
 * shortcut: 6 populations with hand-set weights, not the 138k-neuron FlyWire connectome (too heavy for a phone, see ARCHITECTURE.md).
 */
object FlyRouter : Controller {
    override val name = "fly-router"
    val POP = listOf("memory", "world_model", "goal", "reward", "emotion", "executive")
    // W[i][j]: synapse from population j onto i (MB, PB and LH all project onto FB executive)
    private val W = arrayOf(
        doubleArrayOf(0.0, 0.1, 0.1, 0.2, 0.2, 0.0),
        doubleArrayOf(0.1, 0.0, 0.1, 0.0, 0.0, 0.1),
        doubleArrayOf(0.1, 0.2, 0.0, 0.1, 0.0, 0.1),
        doubleArrayOf(0.1, 0.0, 0.1, 0.0, 0.1, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 0.2, 0.0, 0.0),
        doubleArrayOf(0.3, 0.3, 0.3, 0.2, 0.2, 0.0),
    )

    fun features(task: String, hasImage: Boolean): DoubleArray {
        val t = task.lowercase(); val w = Recall.words(t)
        fun any(vararg k: String) = if (k.any { it in t }) 1.0 else 0.0
        return doubleArrayOf(
            any("remember", "last time", "again", "previous", "before", "my ", "recall"),                      // memory
            minOf(1.0, w.size / 25.0) + (if (hasImage) 0.8 else 0.0) + any("why", "how", "explain", "compare"), // world model
            any("plan", "research", "create", "make", "build", "organize", "write", "find", "and then", "steps"), // goal
            any("should", "best", "better", "worth", "good", "bad"),                                             // reward
            any("urgent", "asap", "now", "quickly", "help", "problem", "error", "broken", "!"),                // emotion (arousal)
            0.3,                                                                                                // executive tonic drive
        )
    }

    fun activation(task: String, hasImage: Boolean, ticks: Int = 100): Map<String, Double> {
        val input = features(task, hasImage)
        val v = DoubleArray(6); val spikes = DoubleArray(6); val s = DoubleArray(6)
        val tau = 10.0; val dt = 1.0
        repeat(ticks) {
            for (i in 0 until 6) {
                val syn = (0 until 6).sumOf { j -> W[i][j] * s[j] } * 3.0
                v[i] += dt * (-v[i] + 2.5 * input[i] + syn) / tau
            }
            for (i in 0 until 6) { s[i] = if (v[i] >= 1.0) 1.0 else 0.0; if (s[i] > 0) { spikes[i]++; v[i] = 0.0 } }
        }
        val rate = spikes.map { it / ticks }
        val total = rate.sum().takeIf { it > 0 } ?: 1.0
        val arousal = input[4]; val valence = if (Regex("error|broken|fail|bad|problem").containsMatchIn(task.lowercase())) -1.0 else 0.2
        val urgency = arousal * maxOf(0.0, -valence)
        return POP.indices.associate { POP[it] to (rate[it] / total) * (1 + urgency) }
    }

    override fun route(task: String, hasImage: Boolean): Route {
        val a = activation(task, hasImage)
        val complexity = a.getValue("world_model") + a.getValue("goal") + a.getValue("reward")
        val local = LocalCommands.parse(task) != null && !hasImage
        val cloud = !local || complexity > 0.75
        val shown = a.filterValues { it >= 0.1 }.mapValues { "%.2f".format(it.value) }
        return Route(cloud, a.getValue("memory") >= 0.1, a, "active=$shown complexity=${"%.2f".format(complexity)} → ${if (cloud) "cloud" else "local"}")
    }
}
