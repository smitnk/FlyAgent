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

class LocalGroundingTest {
    @Test fun detectsNaturalLanguageBrainRequests() {
        assertEquals(100.0, BrainIntent.args("Run brain sugar 100 and explain MN9 using the actual results")!!.getDouble("hz"), 0.0)
        assertEquals("sugar", BrainIntent.args("Run the sugar brain experiment and explain what MN9 means.")!!.getString("stimulate"))
        assertFalse(BrainIntent.args("run the sugar brain experiment")!!.has("hz"))
        assertEquals("info", BrainIntent.args("how many neurons are in the connectome?")!!.getString("action"))
        assertNull(BrainIntent.args("what is 4+4"))
        assertNull(BrainIntent.args("brainstorm video ideas"))
    }

    @Test fun localModelGetsRealSimulationResultsAndNoStaleHistory() {
        val brain = FakeBrain(); val store = FakeStore()
        val tools = Tools(java.io.File(System.getProperty("java.io.tmpdir"), "fa-test2"), store) { brain }
        val local = FakeLlm("qwen", mutableListOf("MN9 fired at 12.5 Hz, so sugar drives feeding."))
        val r = Agent(store, tools::run, { emptyList() }, null, { local })
            .run("Run brain sugar 100 and explain MN9 using the actual results", null, Mode.OFFLINE, ConventionalController, {},
                listOf("user" to "What is 4+4", "assistant" to "8"))
        assertEquals(1, brain.calls.size)                              // app ran the simulator itself
        assertEquals(1, local.lastMsgs.size)                           // old "4+4 = 8" turn not sent
        assertTrue(local.lastMsgs.single().second.contains("MN9 (feeding motor neuron): 12.5 Hz"))
        assertFalse(local.lastMsgs.single().second.contains("Most active")) // long ID list kept out of the small model's prompt
        assertTrue(r.answer.startsWith("MN9 fired at 12.5 Hz")); assertTrue(r.answer.contains("— Simulation output —"))
    }

    @Test fun stopEndsStreamingEarly() {
        val agent = Agent(FakeStore(), FakeTools()::run, { emptyList() }, null, { FakeLlm("qwen", mutableListOf("long answer")) })
        val r = agent.run("tell me a story", null, Mode.OFFLINE, ConventionalController, {}) { agent.stop(); false }
        assertTrue(r.answer.endsWith("(stopped)"))
    }
}
