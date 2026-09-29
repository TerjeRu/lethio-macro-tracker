package com.lethio.macros.domain.model

sealed interface FoodRef {

    val id: Long?

    val kind: Kind

    data class Bundled(override val id: Long) : FoodRef {
        override val kind: Kind get() = Kind.BUNDLED
    }

    data class Custom(override val id: Long) : FoodRef {
        override val kind: Kind get() = Kind.CUSTOM
    }

    data object QuickAdd : FoodRef {
        override val id: Long? get() = null
        override val kind: Kind get() = Kind.QUICK_ADD
    }

    enum class Kind(val value: String) {
        BUNDLED("bundled"),
        CUSTOM("custom"),
        QUICK_ADD("quick");

        companion object {

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
    USER("user");

    companion object {
        private val BY_VALUE: Map<String, DataSource> = entries.associateBy { it.value }

        fun fromValue(value: String?): DataSource? = value?.let { BY_VALUE[it] }
    }
}
