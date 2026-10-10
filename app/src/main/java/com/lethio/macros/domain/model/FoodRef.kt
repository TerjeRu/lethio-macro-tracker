package com.lethio.macros.domain.model

/**
 * What a logged entry points at. Sealed so that impossible states (a custom reference without an
 * id, a quick-add with one) cannot be represented; the mapper alone knows the two-column storage.
 */
sealed interface FoodRef {

    /** Null only for [QuickAdd], which by definition points at nothing. */
    val id: Long?

    val kind: Kind

    /** An item from the shipped food database (`nutrition.db`). */
    data class Bundled(override val id: Long) : FoodRef {
        override val kind: Kind get() = Kind.BUNDLED
    }

    /** A food the user created themselves, stored in `user.db`. */
    data class Custom(override val id: Long) : FoodRef {
        override val kind: Kind get() = Kind.CUSTOM
    }

    /** Typed-in macros with no underlying food record. */
    data object QuickAdd : FoodRef {
        override val id: Long? get() = null
        override val kind: Kind get() = Kind.QUICK_ADD
    }

    /** The persisted discriminator: how to look the food up. Distinct from [DataSource], which is provenance. */
    enum class Kind(val value: String) {
        BUNDLED("bundled"),
        CUSTOM("custom"),
        QUICK_ADD("quick");

        companion object {
            /** Never throws: one bad row must not crash the diary. */
            fun fromValue(value: String): Kind =
                entries.firstOrNull { it.value == value } ?: QUICK_ADD
        }
    }

    companion object {
        fun of(kind: Kind, id: Long?): FoodRef = when (kind) {
            Kind.BUNDLED -> id?.let(::Bundled) ?: QuickAdd
            Kind.CUSTOM -> id?.let(::Custom) ?: QuickAdd
            Kind.QUICK_ADD -> QuickAdd
        }
    }
}

/**
 * The dataset a food's numbers came from, shown on every result and needed for attribution.
 *
 * Must name every value `foods.source` can hold; `DataSourceTest` ties it to `GENERIC_SOURCES` in
 * `tools/audit_nutrition_db.py` (plus `off` and `user`). A new national table goes in both places.
 */
enum class DataSource(val value: String) {
    OPEN_FOOD_FACTS("off"),
    USDA("usda"),
    CIQUAL("ciqual"),
    BLS("bls"),
    BEDCA("bedca"),
    COFID("cofid"),
    MATVARETABELLEN("matvaretabellen"),
    LIVSMEDEL("livsmedel"),
    FINELI("fineli"),
    FRIDA("frida"),
    SWISS("swiss"),
    TCA("tca"),
    CNF("cnf"),
    AFCD("afcd"),
    NEVO("nevo"),
    FOODFILES("foodfiles"),
    USER("user");

    companion object {
        private val BY_VALUE: Map<String, DataSource> = entries.associateBy { it.value }

        /**
         * The source for a stored string, or null for an absent or unknown value. An unknown value
         * is a defect that `DataSourceTest` catches; at runtime it only costs the label.
         */
        fun fromValue(value: String?): DataSource? = value?.let { BY_VALUE[it] }
    }
}
