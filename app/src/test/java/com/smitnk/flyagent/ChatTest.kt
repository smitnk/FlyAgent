package com.smitnk.flyagent

import org.junit.Assert.*
import org.junit.Test

class ChatTest {
    @Test fun smallTalkAnswersBasics() {
        assertTrue(SmallTalk.reply("Hi")!!.contains("FlyAgent"))
        assertEquals("12", SmallTalk.reply("what is 7 + 5?"))
        assertEquals("2.5", SmallTalk.reply("5 / 2"))
        assertNull(SmallTalk.reply("explain quantum physics"))
    }

    @Test fun offlineHiRepliesWithoutAnyModel() {
        val r = Agent(FakeStore(), FakeTools()::run, { emptyList() }).run("hi", null, Mode.OFFLINE, ConventionalController, {})
        assertTrue(r.success); assertTrue(r.answer.startsWith("Hi"))
    }

    @Test fun offlineUsesLocalModelWithoutHistoryAndNeverCloud() {
        val local = FakeLlm("local", mutableListOf("Paris is the capital of France."))
        val cloud = FakeLlm("cloud")
        val r = Agent(FakeStore(), FakeTools()::run, { listOf(cloud) }, null, { local })
            .run("capital of france?", null, Mode.OFFLINE, ConventionalController, {}, listOf("user" to "hi", "assistant" to "Hello!"))
        assertEquals("Paris is the capital of France.", r.answer); assertEquals(0, cloud.calls)
        assertEquals(listOf("user"), local.lastMsgs.map { it.first }) // small model only sees the current question
    }

    @Test fun hybridWithoutApiFallsBackToLocalModel() {
        val local = FakeLlm("local", mutableListOf("offline answer"))
        val r = Agent(FakeStore(), FakeTools()::run, { emptyList() }, null, { local }).run("tell me a joke", null, Mode.HYBRID, ConventionalController, {})
        assertEquals("offline answer", r.answer); assertFalse(r.usedCloud)
    }

    @Test fun cloudGetsChatHistory() {
        val cloud = FakeLlm()
        Agent(FakeStore(), FakeTools()::run, { listOf(cloud) }).run("and tomorrow?", null, Mode.CLOUD, ConventionalController, {}, listOf("user" to "weather today?", "assistant" to "Sunny"))
        assertEquals("weather today?", cloud.lastMsgs.first().second)
    }

    @Test fun offlineUnknownWithoutModelExplainsHowToEnableChat() {
        val r = Agent(FakeStore(), FakeTools()::run, { emptyList() }).run("tell me a joke", null, Mode.OFFLINE, ConventionalController, {})
        assertFalse(r.success); assertTrue(r.answer.contains("load a local model"))
    }
}
