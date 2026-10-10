package com.lethio.macros.domain.model

/**
 * The credit a bundled dataset requires, as its publisher words it. Licence text, not interface
 * copy, so it is not translated: several publishers mandate an exact string.
 *
 * @param title the dataset's full name on the attribution screen
 * @param shortLabel the brief name shown on a search result
 * @param licence the licence or terms it is used under
 * @param required wording the publisher mandates verbatim, or null when only a credit is needed
 * @param url where the dataset is published
 * @param calculationCredit wording required on figures calculated from the data, shown beside
 *   diary totals and in exports
 */
data class DataSourceAttribution(
    val source: DataSource,
    val title: String,
    val shortLabel: String,
    val licence: String,
    val required: String? = null,
    val url: String? = null,
    val licenceUrl: String? = null,
    val downloadUrl: String? = null,
    val calculationCredit: String? = null,
) {
    companion object {
        /**
         * Every bundled dataset, largest contribution first. `DataSourceTest` requires an entry for
         * every [DataSource], so a table cannot ship without its credit. Wording comes from the
         * publishers' terms (see LICENSES/Nutrition-data.txt); do not paraphrase it.
         */
        val ALL: List<DataSourceAttribution> = listOf(
            DataSourceAttribution(
                source = DataSource.OPEN_FOOD_FACTS,
                shortLabel = "Open Food Facts",
                title = "Open Food Facts",
                licence = "Open Database License (ODbL) v1.0",
                required = "Contains information from Open Food Facts, which is made available " +
                    "under the Open Database License (ODbL).",
                url = "https://world.openfoodfacts.org/data",
                licenceUrl = "https://opendatacommons.org/licenses/odbl/1-0/",
                downloadUrl = "https://github.com/TerjeRu/lethio-food-db/releases",
            ),
            DataSourceAttribution(
                source = DataSource.USDA,
                shortLabel = "USDA",
                title = "USDA FoodData Central",
                licence = "Public domain",
                url = "https://fdc.nal.usda.gov/download-datasets",
            ),
            DataSourceAttribution(
                source = DataSource.BLS,
                shortLabel = "BLS",
                title = "Bundeslebensmittelschlüssel (BLS) 4.0",
                licence = "CC BY 4.0 — Max Rubner-Institut",
                url = "https://www.blsdb.de/",
                licenceUrl = "https://creativecommons.org/licenses/by/4.0/",
            ),
            DataSourceAttribution(
                source = DataSource.COFID,
                shortLabel = "CoFID",
                title = "McCance and Widdowson's The Composition of Foods Integrated Dataset",
                licence = "Open Government Licence v3.0",
                // OGL v3.0's own prescribed attribution statement, quoted rather than described.
                required = "Contains public sector information licensed under the Open " +
                    "Government Licence v3.0.",
                url = "https://www.gov.uk/government/publications/" +
                    "composition-of-foods-integrated-dataset-cofid",
                licenceUrl = "https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/",
            ),
            DataSourceAttribution(
                source = DataSource.CIQUAL,
                shortLabel = "Ciqual",
                title = "ANSES-Ciqual French food composition table",
                licence = "Etalab 2.0 / CC BY 4.0",
                url = "https://ciqual.anses.fr/",
                licenceUrl = "https://www.etalab.gouv.fr/licence-ouverte-open-licence/",
            ),
            DataSourceAttribution(
                source = DataSource.NEVO,
                shortLabel = "NEVO",
                title = "NEVO online, version 2025/9.0",
                licence = "RIVM conditions for use of NEVO online version 2025/9.0",
                required = "NEVO online, version 2025/9.0. RIVM, Bilthoven.",
                // One of the two calculation credits RIVM's conditions of use offer.
                calculationCredit = "Based on data from NEVO online version 2025/9.0, RIVM, Bilthoven " +
                    "and other data sources",
                url = "https://www.rivm.nl/en/dutch-food-composition-database",
            ),
            DataSourceAttribution(
                source = DataSource.AFCD,
                shortLabel = "AFCD",
                title = "Australian Food Composition Database, Release 3",
                licence = "CC BY-SA 3.0 AU — Food Standards Australia New Zealand",
                // FSANZ licence clause 4A(b): the licence URI, the Limitation of Data Statement and
                // the country notice with every copy. Both statements are verbatim.
                required = "There are limitations associated with food composition databases. " +
                    "Food composition data used in the database or databases may represent an " +
                    "average of the nutrient content of a particular sample of foods and " +
                    "ingredients, determined at a particular time. The nutrient composition of " +
                    "foods and ingredients can vary substantially between batches and brands " +
                    "because of a number of factors, including changes in season, processing " +
                    "practices and ingredient source, and methods of calculation.\n\n" +
                    "the Work is based on Australian data and Australia data may not be " +
                    "appropriate for use in other countries\n\n" +
                    "Names and units have been normalized, aliases and search indexes added, " +
                    "and records with invalid nutrition values omitted.",
                url = "https://www.foodstandards.gov.au/science-data/monitoringnutrients/afcd",
                licenceUrl = "https://www.foodstandards.gov.au/science-data/monitoringnutrients/afcd/datauserlicenceagreement",
            ),
            DataSourceAttribution(
                source = DataSource.CNF,
                shortLabel = "CNF",
                title = "Canadian Nutrient File 2026",
                licence = "Open Government Licence – Canada",
                // The OGL-Canada's own prescribed attribution statement, with the en dash the
                // licence name itself carries.
                required = "Contains information licensed under the Open Government " +
                    "Licence – Canada.",
                url = "https://open.canada.ca/data/en/dataset/" +
                    "1b6139bd-ed7e-4043-bc28-ff00e10f3109",
                licenceUrl = "https://open.canada.ca/en/open-government-licence-canada",
            ),
            DataSourceAttribution(
                source = DataSource.FINELI,
                shortLabel = "Fineli",
                title = "Fineli — Finnish food composition database, Release 20",
                licence = "CC BY 4.0 — Finnish Institute for Health and Welfare (THL)",
                // The licence line from the data package's own descript.txt, verbatim.
                required = "Copyright 2015 National Institute for Health and Welfare (THL). " +
                    "Licence: Creative Commons Attribution 4.0 (CC-BY 4.0).",
                url = "https://fineli.fi/fineli/en/ohje/19",
                licenceUrl = "https://creativecommons.org/licenses/by/4.0/",
            ),
            DataSourceAttribution(
                source = DataSource.FRIDA,
                shortLabel = "Frida",
                title = "Frida — Danish Food Composition Database, version 6.1",
                // DTU publishes terms requiring source credit, not a named licence.
                licence = "Free use with source credit — no formal licence grant",
                required = "© The Danish Food Composition Database " +
                    "(http://fcdb.fooddata.dk), version 6.1, May 2026.",
                url = "https://frida.fooddata.dk/",
            ),
            DataSourceAttribution(
                source = DataSource.LIVSMEDEL,
                shortLabel = "Livsmedelsdatabasen",
                title = "Livsmedelsdatabasen",
                licence = "CC BY 4.0 — Livsmedelsverket (Swedish Food Agency)",
                // Livsmedelsverket's open-data page names the required credit in one sentence.
                required = "The Swedish Food Agency must be stated as the source.",
                url = "https://www.livsmedelsverket.se/en/about-us/psidata/food-composition-data",
                licenceUrl = "https://creativecommons.org/licenses/by/4.0/",
            ),
            DataSourceAttribution(
                source = DataSource.MATVARETABELLEN,
                shortLabel = "Matvaretabellen",
                title = "Matvaretabellen",
                licence = "Norwegian Licence for Open Government Data (NLOD) 2.0",
                // NLOD 2.0 section 5 supplies this default notice with the licensor named;
                // Matvaretabellen's own API page separately asks to be cited as the source.
                required = "Contains data under the Norwegian licence for Open Government " +
                    "data (NLOD) distributed by Mattilsynet.",
                url = "https://www.matvaretabellen.no/en/",
                licenceUrl = "https://data.norge.no/nlod/en/2.0",
            ),
            DataSourceAttribution(
                source = DataSource.SWISS,
                shortLabel = "Swiss FCDB",
                title = "Swiss Food Composition Database v7.1",
                licence = "Free use including commercial, with source credit — " +
                    "Federal Food Safety and Veterinary Office (FSVO)",
                // The FSVO mandates no particular string, only acknowledgment of the source, so
                // `required` stays null: inventing a mandated wording would misstate the terms.
                url = "https://naehrwertdaten.ch/",
            ),
            DataSourceAttribution(
                source = DataSource.TCA,
                shortLabel = "TCA",
                title = "Tabela da Composição de Alimentos v7.1 (2026)",
                licence = "INSA terms of use — source must be visibly stated",
                // INSA's example wording. The version is part of the credit and changes yearly.
                required = "Fonte: Base de Dados da Composição de Alimentos. " +
                    "Instituto Nacional de Saúde Doutor Ricardo Jorge, I. P.- INSA. " +
                    "v 7.1 - 2026",
                url = "https://portfir-insa.min-saude.pt/",
            ),
            DataSourceAttribution(
                source = DataSource.BEDCA,
                shortLabel = "BEDCA",
                title = "BEDCA",
                licence = "Non-commercial use with attribution",
                // Verbatim, as AESAN/BEDCA's conditions of use require.
                required = "AESAN/BEDCA Base de Datos Española de Composición de Alimentos " +
                    "v1.0 (2010)",
                url = "https://www.bedca.net/",
            ),
            DataSourceAttribution(
                source = DataSource.FOODFILES,
                shortLabel = "FOODfiles",
                title = "New Zealand FOODfiles 2024, version 1",
                licence = "New Zealand Food Composition Database terms of use",
                required = "FOODfiles 2024 v1. © The New Zealand Institute for Plant & Food Research Limited " +
                    "and the Ministry of Health (New Zealand), 2024. Provided by The Bioeconomy Science Institute " +
                    "and the Ministry of Health (New Zealand). Access to and use or supply of this data is subject " +
                    "to the New Zealand Food Composition Database terms of use.",
                url = "https://www.foodcomposition.co.nz/",
                licenceUrl = "https://www.foodcomposition.co.nz/terms/",
            ),
        )

        /** The attribution for [source], or null for sources that need none (a user's own food). */
        fun forSource(source: DataSource): DataSourceAttribution? = ALL.find { it.source == source }

        /**
         * The short name beside a search result, or null for a user's own food or an unknown
         * source. The publisher's name for the table, not the country.
         */
        fun shortLabelFor(source: DataSource): String? = forSource(source)?.shortLabel
    }
}


/**
 * The national datasets behind [values], from each saved nutrient's `sourceOrigin`. Credits follow
 * the saved entry, so they survive a catalogue change.
 */
fun datasetsIn(values: Iterable<Macros>): Set<DataSource> =
    values.flatMap { macros -> macros.nutrients.values.mapNotNull { it.sourceOrigin?.dataset } }
        .mapNotNull(DataSource::fromValue)
        .toSet()

/** The calculation credits owed for [values], in [DataSourceAttribution.ALL] order. */
fun calculationCreditsFor(values: Iterable<Macros>): List<String> = calculationCreditsFor(datasetsIn(values))

/** The calculation credits owed when figures from [used] are shown. */
fun calculationCreditsFor(used: Set<DataSource>): List<String> =
    DataSourceAttribution.ALL.filter { it.source in used }.mapNotNull { it.calculationCredit }

/**
 * What a CSV row carries for its own figures: a dataset's calculation credit when it has one,
 * otherwise its required credit for sources whose terms travel with supplied data (FOODfiles).
 * Empty for every older source, whose credit lives on the Data sources screen.
 */
fun exportCreditFor(macros: Macros): String =
    DataSourceAttribution.ALL.filter { it.source in datasetsIn(listOf(macros)) }
        .mapNotNull { it.calculationCredit ?: it.required.takeIf { _ -> it.source in EXPORT_NOTICE_SOURCES } }
        .joinToString(" ")

/** Sources whose terms require their notice to accompany supplied data, not only the app. */
private val EXPORT_NOTICE_SOURCES = setOf(DataSource.FOODFILES)
