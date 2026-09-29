package com.lethio.macros.domain.model

data class DataSourceAttribution(
    val source: DataSource,
    val title: String,
    val shortLabel: String,
    val licence: String,
    val required: String? = null,
    val url: String? = null,
) {
    companion object {

        val ALL: List<DataSourceAttribution> = listOf(
            DataSourceAttribution(
                source = DataSource.OPEN_FOOD_FACTS,
                shortLabel = "Open Food Facts",
                title = "Open Food Facts",
                licence = "Open Database License (ODbL) v1.0",
                required = "Contains information from Open Food Facts, which is made available " +
                    "under the Open Database License (ODbL).",
                url = "https://world.openfoodfacts.org/data",
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
            ),
            DataSourceAttribution(
                source = DataSource.COFID,
                shortLabel = "CoFID",
                title = "McCance and Widdowson's The Composition of Foods Integrated Dataset",
                licence = "Open Government Licence v3.0",

                required = "Contains public sector information licensed under the Open " +
                    "Government Licence v3.0.",
                url = "https://www.gov.uk/government/publications/" +
                    "composition-of-foods-integrated-dataset-cofid",
            ),
            DataSourceAttribution(
                source = DataSource.CIQUAL,
                shortLabel = "Ciqual",
                title = "ANSES-Ciqual French food composition table",
                licence = "Etalab 2.0 / CC BY 4.0",
                url = "https://ciqual.anses.fr/",
            ),
            DataSourceAttribution(
                source = DataSource.AFCD,
                shortLabel = "AFCD",
                title = "Australian Food Composition Database, Release 3",
                licence = "CC BY-SA 3.0 AU — Food Standards Australia New Zealand",

                required = "There are limitations associated with food composition databases. " +
                    "Food composition data used in the database or databases may represent an " +
                    "average of the nutrient content of a particular sample of foods and " +
                    "ingredients, determined at a particular time. The nutrient composition of " +
                    "foods and ingredients can vary substantially between batches and brands " +
                    "because of a number of factors, including changes in season, processing " +
                    "practices and ingredient source, and methods of calculation.\n\n" +
                    "the Work is based on Australian data and Australia data may not be " +
                    "appropriate for use in other countries",
                url = "https://www.foodstandards.gov.au/science-data/monitoringnutrients/afcd",
            ),
            DataSourceAttribution(
                source = DataSource.CNF,
                shortLabel = "CNF",
                title = "Canadian Nutrient File 2026",
                licence = "Open Government Licence – Canada",

                required = "Contains information licensed under the Open Government " +
                    "Licence – Canada.",
                url = "https://open.canada.ca/data/en/dataset/" +
                    "1b6139bd-ed7e-4043-bc28-ff00e10f3109",
            ),
            DataSourceAttribution(
                source = DataSource.FINELI,
                shortLabel = "Fineli",
                title = "Fineli — Finnish food composition database, Release 20",
                licence = "CC BY 4.0 — Finnish Institute for Health and Welfare (THL)",

                required = "Copyright 2015 National Institute for Health and Welfare (THL). " +
                    "Licence: Creative Commons Attribution 4.0 (CC-BY 4.0).",
                url = "https://fineli.fi/fineli/en/ohje/19",
            ),
            DataSourceAttribution(
                source = DataSource.FRIDA,
                shortLabel = "Frida",
                title = "Frida — Danish Food Composition Database, version 6.1",

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

                required = "The Swedish Food Agency must be stated as the source.",
                url = "https://www.livsmedelsverket.se/en/about-us/psidata/food-composition-data",
            ),
            DataSourceAttribution(
                source = DataSource.MATVARETABELLEN,
                shortLabel = "Matvaretabellen",
                title = "Matvaretabellen",
                licence = "Norwegian Licence for Open Government Data (NLOD) 2.0",

                required = "Contains data under the Norwegian licence for Open Government " +
                    "data (NLOD) distributed by Mattilsynet.",
                url = "https://www.matvaretabellen.no/en/",
            ),
            DataSourceAttribution(
                source = DataSource.SWISS,
                shortLabel = "Swiss FCDB",
                title = "Swiss Food Composition Database v7.1",
                licence = "Free use including commercial, with source credit — " +
                    "Federal Food Safety and Veterinary Office (FSVO)",

                url = "https://naehrwertdaten.ch/",
            ),
            DataSourceAttribution(
                source = DataSource.TCA,
                shortLabel = "TCA",
                title = "Tabela da Composição de Alimentos v7.1 (2026)",
                licence = "INSA terms of use — source must be visibly stated",

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

                required = "AESAN/BEDCA Base de Datos Española de Composición de Alimentos " +
                    "v1.0 (2010)",
                url = "https://www.bedca.net/",
            ),
        )

        fun forSource(source: DataSource): DataSourceAttribution? = ALL.find { it.source == source }

        fun shortLabelFor(source: DataSource): String? = forSource(source)?.shortLabel
    }
}
