package com.smitnk.flyagent

import org.json.JSONArray
import org.json.JSONObject

data class SimResult(val ratesHz: Map<Long, Double>, val totalSpikes: Long, val wallMs: Long, val stimulated: List<Long>, val missing: List<Long>)

/** A loaded connectome you can run experiments on. Real implementation: [ConnectomeBrain] (C++ via JNI). */
interface BrainEngine {
    val label: String          // "subset" or "full brain"
    val source: String         // provenance string stored inside the data file
    val neurons: Int
    val edges: Long
    fun has(id: Long): Boolean
    fun simulate(excite: List<Long>, hz: Double, silence: List<Long>, ms: Double, trials: Int): SimResult
}

/**
 * Experiments on the fly connectome. Connectivity is REAL (FlyWire v783); activity is MODELED (leaky
 * integrate-and-fire, Shiu et al. 2024 parameters). Results are labelled that way everywhere.
 */
object BrainLab {
    /** Sugar-sensing gustatory receptor neurons (right hemisphere) and MN9, from Shiu et al. example.ipynb. */
    val SUGAR = listOf(720575940624963786, 720575940630233916, 720575940637568838, 720575940638202345, 720575940617000768,
        720575940630797113, 720575940632889389, 720575940621754367, 720575940621502051, 720575940640649691,
        720575940639332736, 720575940616885538, 720575940639198653, 720575940620900446, 720575940617937543,
        720575940632425919, 720575940633143833, 720575940612670570, 720575940628853239, 720575940629176663,
        720575940611875570)
    const val MN9 = 720575940660219265
    private val known = SUGAR.withIndex().associate { (i, id) -> id to "sugar GRN ${i + 1}" } + (MN9 to "MN9 (proboscis motor neuron)")

    const val TAG = "[Real connectivity: FlyWire v783 | Modeled activity: LIF simulation, not a recording]"

    fun info(b: BrainEngine?) = if (b == null) "No connectome loaded." else
        "Fly connectome loaded (${b.label}): ${"%,d".format(b.neurons)} neurons, ${"%,d".format(b.edges)} connections.\n" +
        "Data: ${b.source}\nDynamics: leaky integrate-and-fire model (Shiu et al. 2024 parameters). Connectivity is measured; activity is simulated."

    /** args: {"stimulate": "sugar" | [ids], "hz": 100, "silence": [ids], "trials": n, "ms": 1000} */
    fun run(b: BrainEngine?, args: JSONObject): String {
        if (b == null) return "ERROR: no connectome loaded"
        if (args.optString("action") == "info") return info(b)
        val stim = when (val s = args.opt("stimulate")) {
            is JSONArray -> (0 until s.length()).map { s.getLong(it) }
            is String -> if (s.equals("sugar", true) || s.isBlank()) SUGAR else s.split(',', ' ').mapNotNull { it.trim().toLongOrNull() }
            is Number -> listOf(s.toLong())
            else -> SUGAR
        }
        val sil = args.optJSONArray("silence")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
        val hz = args.optDouble("hz", 100.0).coerceIn(1.0, 400.0)
        val ms = args.optDouble("ms", 1000.0).coerceIn(100.0, 2000.0)
        val trials = args.optInt("trials", if (b.neurons > 50_000) 5 else 10).coerceIn(1, 30)
        if (stim.isEmpty()) return "ERROR: no valid FlyWire IDs to stimulate"
        val r = b.simulate(stim, hz, sil, ms, trials)
        return format(b, r, hz, ms, trials, sil)
    }

    fun format(b: BrainEngine, r: SimResult, hz: Double, ms: Double, trials: Int, sil: List<Long>) = buildString {
        appendLine(TAG)
        appendLine("Stimulated ${r.stimulated.size} neuron(s) at ${hz.toInt()} Hz for ${ms.toInt()} ms × $trials trials on the ${b.label} (${"%,d".format(b.neurons)} neurons)" +
            (if (sil.isNotEmpty()) ", silenced ${sil.size}" else "") + ".")
        if (r.missing.isNotEmpty()) appendLine("Not in this dataset: ${r.missing.joinToString()}")
        appendLine("${r.ratesHz.size} neurons fired, ${r.totalSpikes} spikes, computed in ${r.wallMs} ms on this phone.")
        if (b.has(MN9)) appendLine("MN9 (feeding motor neuron): ${"%.1f".format(r.ratesHz[MN9] ?: 0.0)} Hz")
        appendLine("Most active downstream neurons (not stimulated):")
        r.ratesHz.filterKeys { it !in r.stimulated }.entries.sortedByDescending { it.value }.take(10).forEach { (id, hzv) ->
            appendLine("  $id${known[id]?.let { " ($it)" } ?: ""}: ${"%.1f".format(hzv)} Hz")
        }
        if (b.label == "subset") append("Note: subset excludes inputs from the rest of the brain; load the full brain for the complete model.")
    }.trim()
}
