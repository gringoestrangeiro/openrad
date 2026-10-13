package com.openrad.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Mirrors the Rust `tests/localization.rs`: complete translations with identical placeholders. */
class LocalizationTest {
    private fun load(dir: String): Map<String, String> {
        val file = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i)
            e.attributes.getNamedItem("name").nodeValue to e.textContent
        }
    }

    private fun placeholders(text: String) = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyLocaleHasEveryKeyAndTheSamePlaceholders() {
        val english = load("values")
        for (dir in listOf("values-pt", "values-ru", "values-vi")) {
            val other = load(dir)
            assertEquals("keys differ in $dir", english.keys, other.keys)
            for ((key, value) in english) {
                assertEquals("placeholders of $key in $dir", placeholders(value), placeholders(other.getValue(key)))
                assertTrue("empty $key in $dir", other.getValue(key).isNotBlank())
            }
        }
    }

    @Test
    fun multiplePlaceholdersArePositional() {
        for ((key, value) in load("values")) {
            val found = placeholders(value)
            if (found.size > 1) assertTrue("$key must use %1\$s style", found.all { it.contains('$') })
        }
    }
}
