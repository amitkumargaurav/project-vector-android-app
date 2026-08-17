package com.projectvector.app.browser

class CopiedTextCollector {
    private val texts = LinkedHashSet<String>()

    fun add(text: CharSequence?) {
        text?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(texts::add)
    }

    fun toList(): List<String> = texts.toList()
}
