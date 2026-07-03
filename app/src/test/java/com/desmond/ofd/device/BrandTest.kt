package com.desmond.ofd.device

import org.junit.Assert.assertEquals
import org.junit.Test

class BrandTest {

    @Test fun fromBuildBrand_normal_cases() {
        assertEquals(Brand.OnePlus, Brand.fromBuildBrand("OnePlus"))
        assertEquals(Brand.OnePlus, Brand.fromBuildBrand("oneplus"))
        assertEquals(Brand.OPPO, Brand.fromBuildBrand("OPPO"))
        assertEquals(Brand.OPPO, Brand.fromBuildBrand("oppo"))
        assertEquals(Brand.Realme, Brand.fromBuildBrand("realme"))
        assertEquals(Brand.Realme, Brand.fromBuildBrand("Realme"))
        assertEquals(Brand.Other, Brand.fromBuildBrand("Samsung"))
        assertEquals(Brand.Other, Brand.fromBuildBrand(null))
    }
}
