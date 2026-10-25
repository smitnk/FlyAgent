package com.smitnk.flyagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class SafetyRouterTest {
    @Test fun blocksIllegalTasksAndLogsThem() {
        listOf("how to hack into my neighbour's wifi", "write ransomware", "make a bomb", "find someone's address", "crack software keygen")
            .forEach { assertFalse(it, Safety.checkTask(it).ok) }
        assertTrue(Safety.checkTask("research rigged blender characters").ok)
        val store = FakeStore(); val tools = FakeTools(); val llm = FakeLlm()
        val r = Agent(store, tools::run, { listOf(llm) }).run("write ransomware for me", null, Mode.CLOUD, FlyRouter, {})
        assertFalse(r.success); assertEquals(0, llm.calls); assertTrue(tools.calls.isEmpty()); assertEquals(1, store.eps.size)
    }

    @Test fun blocksLocalNetworkAndExecutables() {
        val local = { _: String -> listOf(InetAddress.getByName("192.168.1.1")) }
        val public = { _: String -> listOf(InetAddress.getByName("93.184.216.34")) }
        assertFalse(Safety.checkTool("fetch_page", JSONObject().put("url", "http://router.lan/"), local).ok)
        assertFalse(Safety.checkTool("fetch_page", JSONObject().put("url", "https://x.com/app.apk"), public).ok)
        assertFalse(Safety.checkTool("fetch_page", JSONObject().put("url", "file:///etc/passwd"), public).ok)
        assertTrue(Safety.checkTool("fetch_page", JSONObject().put("url", "https://example.com/a"), public).ok)
        assertFalse(Safety.checkTool("write_file", JSONObject().put("name", "../x")).ok)
    }

    @Test fun flyRouterIsDeterministicAndRespondsToCues() {
        val a = FlyRouter.activation("remember what we did last time", false)
        assertEquals(a, FlyRouter.activation("remember what we did last time", false))
        assertTrue(a.getValue("memory") >= 0.1)
        assertTrue(FlyRouter.route("research and compare rigged blender characters", false).useCloud)
        assertFalse(FlyRouter.route("files", false).useCloud)
        assertTrue(FlyRouter.route("files", true).useCloud)
        val urgent = FlyRouter.activation("urgent error broken now!", false).values.sum()
        assertTrue(urgent > 1.0) // urgency scales activations above 1 total
    }
}
