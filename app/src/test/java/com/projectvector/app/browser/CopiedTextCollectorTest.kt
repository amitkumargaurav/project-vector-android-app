package com.projectvector.app.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class CopiedTextCollectorTest {
    @Test
    fun `stores distinct trimmed non-empty copied texts in insertion order`() {
        val collector = CopiedTextCollector()

        collector.add("  first copied text  ")
        collector.add("")
        collector.add("   ")
        collector.add("second copied text")
        collector.add("first copied text")
        collector.add(null)
        collector.add("third copied text")

        assertEquals(
            listOf("first copied text", "second copied text", "third copied text"),
            collector.toList(),
        )
    }
}
