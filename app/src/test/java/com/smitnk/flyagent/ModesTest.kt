package com.smitnk.flyagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModesTest {
    private val log: (String) -> Unit = {}

    @Test fun offlineNeverCallsModelAndRunsLocalCommand() {
        val llm = FakeLlm(); val tools = FakeTools(); val store = FakeStore()
        val r = Agent(store, tools::run, { listOf(llm) }).run("write a.txt: hi", null, Mode.OFFLINE, FlyRouter, log)
        assertTrue(r.success); assertFalse(r.usedCloud); assertEquals(0, llm.calls); assertEquals(listOf("write_file"), tools.calls)
        assertEquals(1, store.eps.size)
    }

    @Test fun offlineReadsImageWithOnDeviceVision() {
        val llm = FakeLlm()
        val r = Agent(FakeStore(), FakeTools()::run, { listOf(llm) }, { "TEXT: hello" })
            .run("what does this say", Img("AA==", "image/jpeg", ByteArray(1)), Mode.OFFLINE, FlyRouter, log)
        assertEquals("TEXT: hello", r.answer); assertEquals(0, llm.calls)
    }

    @Test fun cloudOnlyUsesModelWithToolsAndSendsImage() {
        val llm = FakeLlm(replies = mutableListOf("{\"tool\":\"web_search\",\"args\":{\"query\":\"x\"}}", "{\"final\":\"see https://example.com\"}"))
        val tools = FakeTools(); val img = Img("AA==", "image/jpeg", ByteArray(1))
        val r = Agent(FakeStore(), tools::run, { listOf(llm) }).run("files", img, Mode.CLOUD, ConventionalController, log)
        assertTrue(r.usedCloud); assertEquals("see https://example.com", r.answer); assertEquals(2, llm.calls)
        assertSame(img, llm.lastImage); assertEquals(listOf("web_search"), tools.calls)
    }

    @Test fun cloudOnlyDoesNotFallBackToLocal() {
        val tools = FakeTools()
        val r = Agent(FakeStore(), tools::run, { listOf(FakeLlm(fail = true)) }).run("files", null, Mode.CLOUD, FlyRouter, log)
        assertFalse(r.success); assertTrue(tools.calls.isEmpty())
    }

    @Test fun cloudFailsOverToNextProvider() {
        val second = FakeLlm("second")
        val r = Agent(FakeStore(), FakeTools()::run, { listOf(FakeLlm(fail = true), second) }).run("plan a trip", null, Mode.CLOUD, FlyRouter, log)
        assertTrue(r.success); assertEquals(1, second.calls)
    }

    @Test fun hybridKeepsSimpleCommandLocal() {
        val llm = FakeLlm()
        val r = Agent(FakeStore(), FakeTools()::run, { listOf(llm) }).run("files", null, Mode.HYBRID, FlyRouter, log)
        assertFalse(r.usedCloud); assertEquals(0, llm.calls)
    }

    @Test fun hybridSendsComplexTaskToCloudAndFallsBackWhenOffline() {
        val ok = FakeLlm()
        assertTrue(Agent(FakeStore(), FakeTools()::run, { listOf(ok) }).run("research and compare rigged blender characters", null, Mode.HYBRID, FlyRouter, log).usedCloud)
        val tools = FakeTools()
        val r = Agent(FakeStore(), tools::run, { listOf(FakeLlm(fail = true)) }).run("search blender rigs", null, Mode.HYBRID, ConventionalController, log)
        assertTrue(r.success); assertFalse(r.usedCloud); assertEquals(listOf("web_search"), tools.calls)
        val img = Agent(FakeStore(), FakeTools()::run, { listOf(FakeLlm(fail = true)) }, { "labels: cat" })
            .run("what is this", Img("AA==", "image/jpeg", ByteArray(1)), Mode.HYBRID, FlyRouter, log)
        assertFalse(img.usedCloud); assertEquals("labels: cat", img.answer) // cloud failed → on-device vision
    }

    @Test fun piiIsRedactedBeforeCloud() {
        val llm = FakeLlm()
        Agent(FakeStore(), FakeTools()::run, { listOf(llm) }).run("my card 4111 1111 1111 1111 password: hunter2 explain", null, Mode.CLOUD, FlyRouter, log)
        val sent = llm.lastMsgs.first().second
        assertFalse(sent.contains("4111")); assertFalse(sent.contains("hunter2"))
    }

    @Test fun feedbackCreatesTestedSkillThenReusesItOffline() {
        val store = FakeStore(); val tools = FakeTools()
        val agent = Agent(store, tools::run, { emptyList() })
        val first = agent.run("search rigged blender characters", null, Mode.OFFLINE, FlyRouter, log)
        val skill = agent.feedback(first.episodeId, true, "", log)!!
        assertEquals("reliable", skill.status); assertEquals(1, skill.version)
        assertTrue(skill.steps.contains("{input}"))
        assertEquals("rigged blender characters", SkillTemplate.expand(skill.steps, "rigged blender characters")[0].second.getString("query"))
        // later session, natural phrasing (not a command) → matched skill runs offline
        tools.calls.clear()
        val r = agent.run("rigged blender characters search", null, Mode.OFFLINE, FlyRouter, log)
        assertTrue(r.success); assertEquals(listOf("web_search"), tools.calls)
        assertEquals(2, agent.feedback(first.episodeId, true, "", log)!!.version) // re-learning bumps version
    }

    @Test fun negativeFeedbackStoresCorrectionUsedLater() {
        val store = FakeStore(); val llm = FakeLlm()
        val agent = Agent(store, FakeTools()::run, { listOf(llm) })
        val r = agent.run("summarise blender news", null, Mode.CLOUD, FlyRouter, log)
        assertNull(agent.feedback(r.episodeId, false, "Use only blender.org sources", log))
        agent.run("summarise blender news", null, Mode.CLOUD, FlyRouter, log)
        assertTrue(llm.lastMsgs.first().second.contains("Use only blender.org sources"))
    }

    @Test fun skillWithBlockedStepFailsTest() {
        val store = FakeStore()
        val id = store.addEpisode("grab", "[{\"tool\":\"fetch_page\",\"args\":{\"url\":\"file:///data/x\"}}]", "x", true, "t")
        assertEquals("failed-test", Agent(store, FakeTools()::run, { emptyList() }).feedback(id, true, "", log)!!.status)
    }

    @Test fun unknownToolFromModelIsRefusedByToolsLayer() =
        assertFalse(Safety.checkTool("install_apk", JSONObject()).ok)
}
