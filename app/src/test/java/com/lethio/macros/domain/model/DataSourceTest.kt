package com.lethio.macros.domain.model

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * `DataSource` must name every value `foods.source` can hold. A missing one returns null from
 * [DataSource.fromValue] and quietly drops the attribution some licences require.
 */
class DataSourceTest {

    /**
     * The source vocabulary the pipeline can write, read from `GENERIC_SOURCES` in
     * `tools/audit_nutrition_db.py` rather than copied, so adding a loader without a `DataSource`
     * fails here. `off` and `user` are not generic sources and are added explicitly.
     */
    private val pipelineSourceValues: Set<String> by lazy {
        val text = repoFile("tools/audit_nutrition_db.py").readText()
        val body =
            Regex("""GENERIC_SOURCES\s*=\s*frozenset\(\s*\{([^}]*)\}\s*\)""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?: error(
                    "GENERIC_SOURCES not found in tools/audit_nutrition_db.py -- if its shape " +
                        "changed, fix this parser rather than deleting the check"
                )
        Regex(""""([a-z0-9_]+)"""").findAll(body).map { it.groupValues[1] }.toSet() +
            setOf("off", "user")
    }

    /** Resolves a repo-relative path regardless of which directory Gradle runs tests from. */
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("could not find $relative walking up from ${File("").absolutePath}")
    }

    @Test
    fun `every source the pipeline can write is representable`() {
        // Fails if a Python loader lands with no Kotlin counterpart -- rows would ship with no
        // provenance label and, for the licensed tables, no attribution. That is exactly the state
        // bls and bedca were in for three builds.
        assertThat(DataSource.entries.map { it.value })
            .containsExactlyElementsIn(pipelineSourceValues)
    }

    @Test
    fun `the pipeline vocabulary was actually parsed, not silently empty`() {
        // A regex that quietly matches nothing would make the test above vacuously agree with an
        // empty set on both sides. Pin the sources that have shipped for several builds.
        assertThat(pipelineSourceValues).containsAtLeast("off", "usda", "ciqual", "bls", "bedca")
    }

    @Test
    fun `bls and bedca resolve, the two that shipped unrepresentable`() {
        assertThat(DataSource.fromValue("bls")).isEqualTo(DataSource.BLS)
        assertThat(DataSource.fromValue("bedca")).isEqualTo(DataSource.BEDCA)
    }

    @Test
    fun `fromValue never throws, whatever it is handed`() {
        // It runs for every row rendered in search results. A throw here would take the screen
        // down over a provenance badge, so an unknown value must degrade to "no label".
        assertThat(DataSource.fromValue(null)).isNull()
        assertThat(DataSource.fromValue("")).isNull()
        // A value no source will ever use: a placeholder drawn from the real vocabulary stops
        // meaning "unknown" as soon as that source is added.
        assertThat(DataSource.fromValue("not-a-real-source")).isNull()
        assertThat(DataSource.fromValue("Off")).isNull()
    }

    @Test
    fun `every source that can appear on a food has a short label for the search row`() {
        // DataSourceAttribution is the one table of source names, so the search row and attribution
        // cannot drift. This is what stops the next national table from shipping with a blank
        // badge.
        val labelled =
            DataSource.entries
                .filter { it != DataSource.USER }
                .filter { DataSourceAttribution.shortLabelFor(it) != null }
        assertThat(labelled)
            .containsExactlyElementsIn(DataSource.entries.filter { it != DataSource.USER })
    }

    @Test
    fun `short labels are short enough to sit on a result row, and distinct`() {
        val labels = DataSourceAttribution.ALL.map { it.shortLabel }
        assertThat(labels).containsNoDuplicates()
        // "Livsmedelsdatabasen" is the longest at 19; the row ellipsises the brand, not this.
        assertThat(labels.maxOf { it.length }).isAtMost(20)
    }

    @Test
    fun `a user's own food has no source label`() {
        assertThat(DataSourceAttribution.shortLabelFor(DataSource.USER)).isNull()
    }

    @Test
    fun `source values are unique`() {
        assertThat(DataSource.entries.map { it.value }).containsNoDuplicates()
    }

    @Test
    fun `every dataset a food can come from carries an attribution`() {
        // USER is the one exemption: a food the user typed in themselves credits nobody.
        val needingCredit = DataSource.entries - DataSource.USER
        val credited = DataSourceAttribution.ALL.map { it.source }
        assertThat(credited).containsExactlyElementsIn(needingCredit)
    }

    @Test
    fun `no dataset is credited twice`() {
        assertThat(DataSourceAttribution.ALL.map { it.source }).containsNoDuplicates()
    }

    @Test
    fun `every attribution names a dataset and a licence`() {
        DataSourceAttribution.ALL.forEach {
            assertThat(it.title).isNotEmpty()
            assertThat(it.licence).isNotEmpty()
        }
    }

    @Test
    fun `BEDCA carries the exact string its terms require`() {
        // AESAN/BEDCA's Condiciones de uso specify this wording. It is not a description of the
        // source and must not be reworded, shortened or translated.
        assertThat(DataSourceAttribution.forSource(DataSource.BEDCA)?.required)
            .isEqualTo("AESAN/BEDCA Base de Datos Española de Composición de Alimentos v1.0 (2010)")
    }

    @Test
    fun `Open Food Facts credits ODbL, which its licence requires`() {
        val odbl = DataSourceAttribution.forSource(DataSource.OPEN_FOOD_FACTS)?.required
        assertThat(odbl).contains("Open Food Facts")
        assertThat(odbl).contains("Open Database License")
    }

    @Test
    fun `forSource returns null for a user's own food`() {
        assertThat(DataSourceAttribution.forSource(DataSource.USER)).isNull()
    }

    @Test
    fun `AFCD identifies modifications and links its agreement`() {
        val attribution = DataSourceAttribution.forSource(DataSource.AFCD)!!
        assertThat(attribution.required)
            .contains(
                "Names and units have been normalized, aliases and search indexes added, " +
                    "and records with invalid nutrition values omitted."
            )
        assertThat(attribution.licenceUrl)
            .isEqualTo(
                "https://www.foodstandards.gov.au/science-data/monitoringnutrients/afcd/datauserlicenceagreement"
            )
    }

    @Test
    fun `OFF attribution provides license and transformed data links`() {
        val attribution = DataSourceAttribution.forSource(DataSource.OPEN_FOOD_FACTS)!!
        assertThat(attribution.licenceUrl)
            .isEqualTo("https://opendatacommons.org/licenses/odbl/1-0/")
        assertThat(attribution.downloadUrl)
            .isEqualTo("https://github.com/TerjeRu/lethio-food-db/releases")
    }
}
