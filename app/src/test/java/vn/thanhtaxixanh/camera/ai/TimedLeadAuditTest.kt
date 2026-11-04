package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class TimedLeadAuditTest {
    private val distances = listOf(
        6.0,10.0,14.0,18.0,25.0,28.0,
        32.0,42.0,55.0,65.0,85.0,110.0
    )
    private fun pairs() = distances.mapIndexed { i, distance ->
        TimedLeadAudit.Sample(
            "F${i+100}",
            1000L+i*200L,
            1070L+i*200L,
            distance,
            distance+1.0
        )
    }

    @Test fun realClockPairDiagnosticComputesRangeStatisticsButStaysUnverified() {
        val report=TimedLeadAudit.inspect(pairs())
        assertEquals(
            TimedLeadAudit.Status.SYNCHRONIZED_STATISTICS_UNVERIFIED,
            report.status)
        assertEquals(12,report.samples)
        assertEquals(70L,report.maxSkewMs)
        assertEquals(70L,report.p95SkewMs)
        assertEquals(1.0,
            report.distanceAudit!!.meanAbsoluteErrorMeters!!,0.0)
        assertFalse(report.mayEnableAdas)
    }

    @Test fun excessiveSkewIsRejectedBeforeMetricStatistics() {
        val bad=pairs().toMutableList()
        bad[2]=bad[2].copy(predictionAtMs=bad[2].referenceAtMs+125)
        val report=TimedLeadAudit.inspect(bad)
        assertEquals(TimedLeadAudit.Status.EXCESSIVE_SKEW,report.status)
        assertEquals(125L,report.maxSkewMs)
        assertNull(report.distanceAudit)
        assertFalse(report.mayEnableAdas)
    }

    @Test fun clockRegressionAndBadTimestampsFailClosed() {
        val base=pairs()
        val reversed=base.toMutableList()
        reversed[3]=reversed[3].copy(referenceAtMs=reversed[2].referenceAtMs)
        assertEquals(TimedLeadAudit.Status.UNORDERED_TIMESTAMPS,
            TimedLeadAudit.inspect(reversed).status)
        val negative=base.toMutableList()
        negative[0]=negative[0].copy(predictionAtMs=-1L)
        assertEquals(TimedLeadAudit.Status.INVALID_TIMESTAMPS,
            TimedLeadAudit.inspect(negative).status)
        val repeated=base.toMutableList()
        repeated[1]=repeated[1].copy(sampleId=base[0].sampleId)
        assertEquals(TimedLeadAudit.Status.INVALID_TIMESTAMPS,
            TimedLeadAudit.inspect(repeated).status)
    }

    private fun textFixture(samples: List<TimedLeadAudit.Sample> = pairs()) =
        (listOf(
            "TTXLEAD02",
            "meta,measured-2026,laser-01,${"a".repeat(64)}",
            "clock,shared-monotonic-ms,session-01") +
            samples.map { s ->
                "sample,${s.sampleId},${s.referenceAtMs},${s.predictionAtMs}," +
                    "${s.referenceMeters},${s.predictedMeters}"
            }).joinToString("\n")
    private fun read(content: String) = LeadDistanceFixture.read(
        ByteArrayInputStream(content.toByteArray()))

    @Test fun timedLeadCsvParsesAndPreservesV1Compatibility() {
        val evidence=read(textFixture())
        assertNotNull(evidence.timingAudit)
        assertEquals(TimedLeadAudit.Status.SYNCHRONIZED_STATISTICS_UNVERIFIED,
            evidence.timingAudit!!.status)
        assertFalse(evidence.timingAudit!!.mayEnableAdas)
        assertEquals(1.0,
            evidence.audit().meanAbsoluteErrorMeters!!,0.0)
        val legacy=(listOf(
            "TTXLEAD01",
            "meta,measured-2026,laser-01,${"a".repeat(64)}") +
            pairs().map {
                "sample,${it.sampleId},${it.referenceMeters},${it.predictedMeters}"
            }).joinToString("\n")
        val old=read(legacy)
        assertNull(old.timingAudit)
        assertEquals(12,old.observations.size)
    }

    @Test fun timebaseLieBadClockOrUnsortedCsvNeverPassesImport() {
        val bad = listOf(
            textFixture().replace(
                "shared-monotonic-ms","wall-clock-epoch-ms"),
            textFixture().replace(
                "session-01","x,bad"),
            textFixture().replace(
                "sample,F100,1000,1070","sample,F100,1000,4000"),
            textFixture().replace(
                "sample,F100,1000,1070","sample,F100,-1,1070"),
            textFixture().replace(
                "sample,F100,1000,1070","sample,F100,NaN,1070"),
            textFixture().lines().take(14).joinToString("\n")
        )
        for (broken in bad) {
            try {
                read(broken)
                fail("Time-invalid lead fixture was accepted")
            } catch (_: Exception) {}
        }
    }
}
