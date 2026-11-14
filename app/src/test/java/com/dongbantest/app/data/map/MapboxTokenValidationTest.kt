package com.dongbantest.app.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapboxTokenValidationTest {
    @Test fun publicTokenIsAccepted() {
        assertNull(validateMapboxToken("pk.test1234"))
        assertNull(validateMapboxToken("pk.eyJ1IjoidGVzdCIsImEiOiJ0ZXN0MTIzNCJ9.abcDEF-_123"))
    }

    @Test fun secretTokenHasDistinctError() {
        assertEquals(MapboxTokenError.SECRET_TOKEN, validateMapboxToken("sk.test1234567"))
    }

    @Test fun malformedTokensAreInvalid() {
        assertEquals(MapboxTokenError.INVALID, validateMapboxToken("test1234567"))
        assertEquals(MapboxTokenError.INVALID, validateMapboxToken("pk."))
        assertEquals(MapboxTokenError.INVALID, validateMapboxToken("pk.te st1234"))
        assertEquals(MapboxTokenError.INVALID, validateMapboxToken("pk.test1234\n"))
        assertEquals(MapboxTokenError.INVALID, validateMapboxToken("pk.테스트1234567"))
        assertEquals(MapboxTokenError.INVALID, validateMapboxToken("pk." + "a".repeat(600)))
        assertEquals(MapboxTokenError.INVALID, validateMapboxToken("PK.test1234"))
    }
}
