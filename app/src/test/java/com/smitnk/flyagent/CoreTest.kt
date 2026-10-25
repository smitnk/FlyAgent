package com.smitnk.flyagent

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun parsesToolAction() {
        val a = ActionParser.parse("Sure:\n```json\n{\"tool\":\"web_search\",\"args\":{\"query\":\"blender rigs\"}}\n```")!!
        assertEquals("web_search", a.tool); assertEquals("blender rigs", a.args.getString("query"))
    }
    @Test fun parsesFinalAndRejectsGarbage() {
        assertEquals("done", ActionParser.parse("{\"final\":\"done\"}")!!.answer)
        assertNull(ActionParser.parse("no json here"))
        assertNull(ActionParser.parse("{broken"))
    }
    @Test fun blocksPathTraversal() {
        assertTrue(SafeName.ok("notes.md"))
        assertFalse(SafeName.ok("../secrets")); assertFalse(SafeName.ok("a/b.txt")); assertFalse(SafeName.ok(".hidden"))
    }
    @Test fun unwrapsSearchLinks() {
        val html = """<a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fblender.org%2Fx&amp;rut=1">Blender <b>Rigs</b></a>"""
        assertEquals(listOf("Blender Rigs" to "https://blender.org/x"), SearchParser.parse(html))
    }
    @Test fun recallRanksByOverlap() {
        val eps = listOf(Episode(1, 0, "find rigged blender characters", "", "ok", true), Episode(2, 0, "weather today", "", "ok", true))
        assertEquals(listOf(1L), Recall.rank("blender characters again", eps, 3).map { it.id })
    }
    @Test fun sha256Known() = assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Tools.sha256(ByteArray(0)))
}
