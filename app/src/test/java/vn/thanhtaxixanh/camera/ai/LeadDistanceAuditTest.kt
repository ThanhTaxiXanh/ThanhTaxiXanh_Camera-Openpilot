package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class LeadDistanceAuditTest {
    private val samples = listOf(6.0,10.0,14.0,18.0,25.0,28.0,
        32.0,42.0,55.0,65.0,85.0,110.0).mapIndexed { index, value ->
        LeadDistanceAudit.Observation("M${index+100}",value,value+1.0)
    }

    @Test fun independentDistanceStatsAreDeterministicButCannotAuthorizeWarnings() {
        val report=LeadDistanceAudit.inspect(samples)
        assertEquals(LeadDistanceAudit.Status.STATISTICS_ONLY_UNVERIFIED,
            report.status)
        assertEquals(12,report.count)
        assertEquals(104.0,report.referenceRangeMeters!!,0.0)
        assertEquals(1.0,report.meanAbsoluteErrorMeters!!,0.00001)
        assertEquals(1.0,report.rootMeanSquareErrorMeters!!,0.00001)
        assertEquals(1.0,report.p95AbsoluteErrorMeters!!,0.00001)
        assertEquals(1.0,report.signedBiasMeters!!,0.00001)
        assertTrue(report.meanAbsolutePercentError!! > 0.0)
        assertFalse(report.mayEnableAdas)
    }

    @Test fun sampleShortageDuplicateIdsAndNarrowRangesAreRejected() {
        assertEquals(LeadDistanceAudit.Status.INSUFFICIENT,
            LeadDistanceAudit.inspect(samples.take(11)).status)
        assertEquals(LeadDistanceAudit.Status.INVALID_DATA,
            LeadDistanceAudit.inspect(samples + samples.first()).status)
        assertEquals(LeadDistanceAudit.Status.POOR_RANGE_COVERAGE,
            LeadDistanceAudit.inspect(samples.mapIndexed { i,s ->
                s.copy(referenceMeters=20.0+i*.1)
            }).status)
    }

    @Test fun invalidMetricEstimatesNeverBecomeStatistics() {
        assertEquals(LeadDistanceAudit.Status.INVALID_DATA,
            LeadDistanceAudit.inspect(samples.mapIndexed { i,s ->
                if (i==0) s.copy(predictedMeters=Double.NaN) else s
            }).status)
        assertEquals(LeadDistanceAudit.Status.INVALID_DATA,
            LeadDistanceAudit.inspect(samples.mapIndexed { i,s ->
                if (i==0) s.copy(referenceMeters=-1.0) else s
            }).status)
    }

    private fun fixture() =
        (listOf("TTXLEAD01",
            "meta,measured-2026,laser-range-01,${"a".repeat(64)}") +
            samples.map {
                "sample,${it.sampleId},${it.referenceMeters},${it.predictedMeters}"
            }).joinToString("\n")

    private fun read(value: String) = LeadDistanceFixture.read(
        ByteArrayInputStream(value.toByteArray()))

    @Test fun boundedLocalLeadCsvCarriesClaimedModelHashButNotTrustCertification() {
        val evidence=read(fixture())
        assertEquals("measured-2026",evidence.datasetId)
        assertEquals("laser-range-01",evidence.referenceInstrumentId)
        assertEquals("a".repeat(64),evidence.claimedModelSha256)
        assertEquals(12,evidence.observations.size)
        assertEquals(LeadDistanceAudit.Status.STATISTICS_ONLY_UNVERIFIED,
            evidence.audit().status)
        assertFalse(evidence.audit().mayEnableAdas)
    }

    @Test fun malformedLeadCsvCannotEnterComparison() {
        listOf(
            fixture().replace("TTXLEAD01","XLEAD01"),
            fixture().replace("laser-range-01","bad,input"),
            fixture().replace("laser-range-01","bad☂"),
            fixture().replace("a".repeat(64),"z".repeat(64)),
            fixture().lines().take(13).joinToString("\n"),
            fixture().replace("sample,M100,6.0,7.0","sample,M100,6.0,NaN"),
            "A".repeat(LeadDistanceFixture.MAX_BYTES + 1)
        ).forEach { broken ->
            try {
                read(broken)
                fail("Malformed external measurement CSV was accepted")
            } catch (_: Exception) {}
        }
    }
}
