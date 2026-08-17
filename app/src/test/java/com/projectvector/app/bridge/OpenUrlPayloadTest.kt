package com.projectvector.app.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUrlPayloadTest {
    @Test
    fun `http and https urls are supported`() {
        assertTrue(isSupportedHttpUrl("http://example.com/syllabus"))
        assertTrue(isSupportedHttpUrl("https://example.com/syllabus?query=math"))
    }

    @Test
    fun `non http urls are rejected`() {
        assertFalse(isSupportedHttpUrl("ftp://example.com/file"))
        assertFalse(isSupportedHttpUrl("mailto:test@example.com"))
        assertFalse(isSupportedHttpUrl("javascript:alert(1)"))
        assertFalse(isSupportedHttpUrl("vector://goal/123"))
    }

    @Test
    fun `urls without a host are rejected`() {
        assertFalse(isSupportedHttpUrl("https://"))
        assertFalse(isSupportedHttpUrl("https:syllabus"))
        assertFalse(isSupportedHttpUrl("not a url"))
    }

    @Test
    fun `open url payload accepts in app browser presentation`() {
        val payload = JSONObject()
            .put("url", "  https://example.com/syllabus  ")
            .put("presentation", "in_app_browser")
            .toOpenUrlPayload()

        assertEquals("https://example.com/syllabus", payload.url)
        assertEquals("in_app_browser", payload.presentation)
    }

    @Test
    fun `open url payload rejects unsupported url`() {
        val error = runCatching {
            JSONObject()
                .put("url", "javascript:alert(1)")
                .put("presentation", "in_app_browser")
                .toOpenUrlPayload()
        }.exceptionOrNull()

        assertEquals("Unsupported URL", error?.message)
    }

    @Test
    fun `open url payload rejects unsupported presentation`() {
        val error = runCatching {
            JSONObject()
                .put("url", "https://example.com/syllabus")
                .put("presentation", "external_browser")
                .toOpenUrlPayload()
        }.exceptionOrNull()

        assertEquals("Unsupported presentation", error?.message)
    }
}
