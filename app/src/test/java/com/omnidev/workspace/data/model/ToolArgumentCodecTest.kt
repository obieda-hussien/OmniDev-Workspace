package com.omnidev.workspace.data.model

import org.junit.Assert.*
import org.junit.Test

class ToolArgumentCodecTest {
    @Test fun `malformed blank null and nonobject payloads fail closed`() {
        for (raw in listOf("", "not JSON", "{broken}", "[]", "null", "42")) {
            assertNotNull(raw, ToolArgumentCodec.decode(raw).error)
        }
    }
    @Test fun `optional null object fields become omissions but nested null remains data`() {
        val decoded = ToolArgumentCodec.decode("""{"path":"file","optional":null,"obj":{"x":null}}""")
        assertNull(decoded.error)
        assertEquals(mapOf("path" to "file", "obj" to """{"x":null}"""), decoded.arguments)
    }

    @Test fun `only omitted discovery arguments get an empty object`() {
        for (raw in listOf(null, "", "  ")) {
            assertNull(ToolArgumentCodec.decodeProviderCall("discover_tools", raw).error)
            assertNotNull(ToolArgumentCodec.decodeProviderCall("patch_file_content", raw).error)
        }
        for (raw in listOf("{", "null", "[]")) {
            assertNotNull(ToolArgumentCodec.decodeProviderCall("discover_tools", raw).error)
        }
    }

    @Test fun `valid empty object is distinct from a decoding failure`() {
        assertNull(ToolArgumentCodec.decode("{}").error)
        assertTrue(ToolArgumentCodec.decode("{}").arguments.isEmpty())
    }
    @Test fun `nested values and primitives survive map transport`() {
        val decoded = ToolArgumentCodec.decode("""{"text":"hi","count":2,"flag":true,"items":[1,2],"obj":{"a":1}}""")
        assertNull(decoded.error)
        assertEquals("2", decoded.arguments["count"])
        assertEquals("true", decoded.arguments["flag"])
        assertEquals("[1,2]", decoded.arguments["items"])
        assertEquals("{\"a\":1}", decoded.arguments["obj"])
    }
}
