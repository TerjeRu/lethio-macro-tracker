package com.lethio.macros.data.food

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Keeps the Python tools scoring the same bm25 ladder the app ships.
 *
 * [FoodDao] weights the fourth `food_search` column, `search_extra`, at `0.0`: it makes a row MATCH
 * without changing what wins. FTS5 defaults an unspecified weight to 1.0, so a tool passing three
 * weights would score differently. Both sides are read from the real files. `app/build.gradle.kts`
 * declares the tools as test inputs, so a Python-only change reruns this.
 */
class SearchLadderMirrorTest {

    /**
     * The bm25 weights the app actually ships, read out of [FoodDao]'s source.
     *
     * Not `FoodDao`'s constants at runtime: the ladder lives inside `@Query` string literals, so
     * the only way to compare it against the Python is to read the same text a reviewer would.
     */
    private val appWeights: String by lazy {
        val found =
            BM25.findAll(repoPath(FOOD_DAO).readText())
                .map { it.groupValues[1].normaliseWeights() }
                .toList()
        check(found.isNotEmpty()) {
            "no bm25(food_search, ...) found in $FOOD_DAO -- if the ladder moved, point this test " +
                "at its new home rather than deleting the check"
        }
        val distinct = found.distinct()
        check(distinct.size == 1) {
            "FoodDao states the bm25 weights ${distinct.size} different ways ($distinct). Fix that " +
                "first -- the app has to agree with itself before the tools can agree with it."
        }
        distinct.single()
    }

    private data class Occurrence(val file: String, val line: Int, val weights: String)

    /** Every `bm25(food_search, ...)` under `tools/`, with enough context to name the offender. */
    private val toolOccurrences: List<Occurrence> by lazy {
        repoPath("tools")
            .listFiles { f -> f.extension == "py" }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file ->
                file.readLines().flatMapIndexed { index, line ->
                    BM25.findAll(line).map {
                        Occurrence(file.name, index + 1, it.groupValues[1].normaliseWeights())
                    }
                }
            }
    }

    /**
     * Resolves a repo-relative file or directory regardless of which directory Gradle runs from.
     * Same walker as [com.lethio.macros.domain.model.DataSourceTest], widened to accept a directory
     * because this test scans one.
     */
    private fun repoPath(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("could not find $relative walking up from ${File("").absolutePath}")
    }

    @Test
    fun `every measurement tool scores the ladder the app ships`() {
        // A tool that scores a different ladder does not measure this app.
        val divergent = toolOccurrences.filter { it.weights != appWeights }
        assertThat(divergent.map { "${it.file}:${it.line} has (${it.weights})" }).isEmpty()
    }

    @Test
    fun `the app states its weights once, not two ways`() {
        // FoodDao carries the ladder twice (primary and fallback shelves). They must agree with
        // each other before it is meaningful to ask whether the tools agree with them.
        val inDao =
            BM25.findAll(repoPath(FOOD_DAO).readText())
                .map { it.groupValues[1].normaliseWeights() }
                .toSet()
        assertThat(inDao).hasSize(1)
    }

    @Test
    fun `the scan actually read the tools, and did not silently match nothing`() {
        // A regex that quietly matches nothing would make the test above vacuously green.
        assertThat(toolOccurrences.size).isAtLeast(3)
        assertThat(toolOccurrences.map { it.file })
            .containsAtLeast("audit_nutrition_db.py", "export_search_results.py")
    }

    @Test
    fun `the weights parsed are the four this app reasons about`() {
        // Guards the parser itself. If normaliseWeights ever returned "" for everything, the
        // comparison above would compare two empty strings and pass.
        assertThat(appWeights.split(",")).hasSize(4)
        assertThat(appWeights).isEqualTo("5.0,1.0,3.0,0.0")
    }

    private companion object {
        const val FOOD_DAO = "app/src/main/java/com/lethio/macros/data/food/FoodDao.kt"

        /** Matches the call in Kotlin `@Query` strings and in Python SQL literals alike. */
        val BM25 = Regex("""bm25\(\s*food_search\s*,([^)]*)\)""")

        /** Whitespace and formatting must not read as a divergence; the numbers must. */
        fun String.normaliseWeights(): String = split(",").joinToString(",") { it.trim() }
    }
}
