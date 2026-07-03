package com.desmond.ofd.device

/** Brand grouping for the detected device. */
enum class Brand(val display: String) {
    OnePlus("OnePlus"),
    OPPO("OPPO"),
    Realme("realme"),
    Other("Other");

    companion object {
        /** Map a `Build.BRAND` / `ro.product.brand` string to a [Brand]. */
        fun fromBuildBrand(raw: String?): Brand = when (raw?.lowercase()) {
            "oneplus" -> OnePlus
            "oppo" -> OPPO
            "realme" -> Realme
            else -> Other
        }
    }
}
