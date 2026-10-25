package com.smitnk.flyagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fake engine standing in for the JNI connectome in JVM tests (the C++ engine is tested natively in CI). */
class FakeBrain(override val label: String = "subset") : BrainEngine {
    val calls = mutableListOf<Triple<List<Long>, Double, List<Long>>>()
    override val source = "test"
    override val neurons = 100
    override val edges = 1000L
    override fun has(id: Long) = id == BrainLab.MN9 || id in BrainLab.SUGAR || id == 42L
    override fun simulate(excite: List<Long>, hz: Double, silence: List<Long>, ms: Double, trials: Int): SimResult {
        calls += Triple(excite, hz, silence)
        val found = excite.filter(::has)
        return SimResult(found.associateWith { hz } + mapOf(BrainLab.MN9 to 12.5, 42L to 30.0), 999, 7, found, excite.filterNot(::has))
    }
}

class BrainTest {
    @Test fun parsesBrainCommands() {
        assertEquals("info", LocalCommands.parse("brain")!!.second.getString("action"))
        val s = LocalCommands.parse("brain sugar 150")!!.second
        assertEquals("sugar", s.getString("stimulate")); assertEquals(150.0, s.getDouble("hz"), 0.0)
        val st = LocalCommands.parse("brain stim 720575940624963786,42 80")!!.second
        assertEquals("720575940624963786,42", st.getString("stimulate")); assertEquals(80.0, st.getDouble("hz"), 0.0)
        val sil = LocalCommands.parse("brain silence 720575940624963786")!!.second
        assertEquals(720575940624963786, sil.getJSONArray("silence").getLong(0)); assertFalse(sil.has("hz"))
        assertNull(LocalCommands.parse("brainstorm video ideas")) // not a brain command
    }

    @Test fun sugarExperimentReportsMn9AndLabelsModelVsData() {
        val b = FakeBrain()
        val out = BrainLab.run(b, JSONObject().put("stimulate", "sugar").put("hz", 100))
        assertEquals(BrainLab.SUGAR, b.calls.single().first)
        assertTrue(out.startsWith(BrainLab.TAG)); assertTrue(out.contains("Real connectivity")); assertTrue(out.contains("Modeled activity"))
        assertTrue(out.contains("MN9 (feeding motor neuron): 12.5 Hz"))
        assertTrue(out.contains("42: 30.0 Hz")); assertTrue(out.contains("subset excludes inputs"))
    }

    @Test fun reportsUnknownIdsAndClampsInputs() {
        val b = FakeBrain("full brain")
        val out = BrainLab.run(b, JSONObject().put("stimulate", "42,7").put("hz", 9999))
        assertEquals(400.0, b.calls.single().second, 0.0)
        assertTrue(out.contains("Not in this dataset: 7")); assertFalse(out.contains("subset excludes"))
        assertTrue(BrainLab.run(null, JSONObject()).startsWith("ERROR"))
    }

    @Test fun agentRunsBrainOfflineAndStoresVerifiedResult() {
        val store = FakeStore(); val brain = FakeBrain()
        val tools = Tools(java.io.File(System.getProperty("java.io.tmpdir"), "fa-test"), store) { brain }
        val r = Agent(store, tools::run, { emptyList() }).run("brain sugar", null, Mode.OFFLINE, ConventionalController, {})
        assertTrue(r.success); assertTrue(r.answer.contains("MN9")); assertEquals(1, brain.calls.size)
        assertTrue(store.eps.single().steps.contains("brain_sim"))
    }

    @Test fun stripsQwenThinking() = assertEquals("Paris.", LocalLlm.stripThink("<think>\nhmm\n</think>\n\nParis."))
}
