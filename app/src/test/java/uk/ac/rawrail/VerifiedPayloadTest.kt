package uk.ac.rawrail
import org.junit.Test
import org.junit.Assert.*
import uk.ac.rawrail.data.*

class VerifiedPayloadTest {
    private val parser = DarwinPublicClient()
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    @Test fun parsesVerifiedStaffPayload() {
        val board = parser.parseBoard(fixture("staff.json"), "LST")
        assertEquals("LST", board.crs)
        assertEquals(false, board.platformsAreHidden)
        assertFalse(board.services.isEmpty())
        val service = board.services.first { it.rid == "202609206737427" }
        assertEquals("A", service.rawPlatform)
        assertEquals(false, service.platformIsHidden)
        assertEquals(false, service.serviceIsSuppressed)
        assertEquals(true, service.futureDelay)
        assertEquals("TD", service.departureSource)
        assertEquals("2026-09-20T18:15:23", service.actualDeparture)
        assertEquals(listOf("ABW"), service.destinationCrs)
        assertEquals(9, service.coachLoading.size)
        assertTrue(service.loading!!.contains("51%"))
        assertEquals(null, service.expectedDeparture)
    }
    @Test fun publicMissingSuppressionIsUnknown() {
        val board = parser.parseBoard(fixture("public.json"), "LST")
        assertNull(board.platformsAreHidden)
        assertTrue(board.services.all { it.platformIsHidden == null && it.serviceIsSuppressed == null })
        assertTrue(board.services.any { it.coachLoading.isNotEmpty() })
    }
    @Test fun supportsStaffCallingLocationsAndSpecifiedFalse() {
        val board = parser.parseBoard("""{"trainServices":[{"rid":"a","std":"2026-09-20T18:42:00","atd":"0001-01-01T00:00:00","atdSpecified":false,"subsequentLocations":[{"locationName":"St Pancras","crs":"STP","st":"19:30"}]}]}""", "RYS")
        assertNull(board.services.single().actualDeparture)
        assertEquals("STP", board.services.single().subsequentCallingPoints.single().crs)
    }
    @Test fun preservesDarwinReasonCodesAndLocations() {
        val board = parser.parseBoard(
            """{"trainServices":[{"rid":"reason-test","delayReason":{"Value":501,"tiploc":"HITCHIN","near":true},"cancelReason":{"Value":502,"tiploc":"RYSTON","near":false},"diversion":{"reason":{"Value":503,"tiploc":"CAMBDGE","near":false}},"subsequentLocations":[{"locationName":"Royston","delayReason":{"Value":504,"tiploc":"RYSTON","near":false}}]}]}""",
            "RYS"
        )
        val service = board.services.single()
        assertEquals(501, service.delayReasonCode)
        assertEquals("HITCHIN", service.delayReasonTiploc)
        assertEquals(true, service.delayReasonNear)
        assertEquals(502, service.cancelReasonCode)
        assertEquals("RYSTON", service.cancelReasonTiploc)
        assertEquals(503, service.diversionReasonCode)
        assertEquals(504, service.subsequentCallingPoints.single().delayReasonCode)
    }
    @Test fun resolvesReasonCodesToDarwinText() {
        val board = parser.parseBoard(
            """{"trainServices":[{"rid":"reason-text","delayReason":{"Value":812,"tiploc":"HITCHIN","near":true},"cancelReason":{"Value":901},"subsequentLocations":[{"locationName":"Royston","delayReason":{"Value":812}}]}]}""",
            "RYS"
        )
        val reasons = parser.parseReasonCodeList(
            """[{"code":812,"lateReason":"This train has been delayed by a signalling fault"},{"code":901,"cancReason":"This train has been cancelled because of a train fault"}]"""
        )
        val service = enrichReasonDescriptions(board, reasons).services.single()
        assertEquals("This train has been delayed by a signalling fault", service.delayReason)
        assertEquals("This train has been cancelled because of a train fault", service.cancelReason)
        assertEquals("This train has been delayed by a signalling fault", service.subsequentCallingPoints.single().delayReason)
        assertEquals(812, service.delayReasonCode)
        assertEquals("HITCHIN", service.delayReasonTiploc)
    }
}
