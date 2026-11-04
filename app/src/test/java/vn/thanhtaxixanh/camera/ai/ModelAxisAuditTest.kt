package vn.thanhtaxixanh.camera.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** Every sample is synthetic; none identifies xADAS native axes. */
class ModelAxisAuditTest {
    private fun points(
        modelYSign: Double = -1.0,
        modelZSign: Double = 1.0,
        upScale: Double = 1.0,
        depthOffset: Double = 0.0
    ): List<ModelAxisAudit.Sample> {
        var index=0
        return listOf(4.0, 12.0, 24.0, 48.0).flatMap { distance ->
            listOf(-2.0,0.0,2.0).map { right ->
                val up = when (index%3) {
                    0 -> -.9
                    1 -> 0.0
                    else -> .9
                }
                ModelAxisAudit.Sample(
                    "S${++index + 100}",
                    distance + depthOffset,
                    modelYSign * right,
                    modelZSign * up * upScale,
                    distance,
                    right,
                    up)
            }
        }
    }

    @Test fun axisSignsMayBeRankedWithoutGrantingTrust() {
        val report=ModelAxisAudit.inspect(points())
        assertEquals(ModelAxisAudit.Status.MATCH_UNVERIFIED,report.status)
        assertEquals(ModelAxisAudit.Hypothesis.RIGHT_NEGATIVE_UP_POSITIVE,
            report.bestHypothesis)
        assertEquals(12,report.count)
        assertEquals(0.0,report.rmsMeters!!,.00000001)
        assertTrue(report.runnerUpRmsMeters!! > .5)
        assertFalse(report.mayEnableAdas)
    }

    @Test fun incorrectForwardRangeAndDegenerateSamplesFailClosed() {
        val wrong=ModelAxisAudit.inspect(points(depthOffset=12.0))
        assertEquals(ModelAxisAudit.Status.HIGH_RESIDUAL,wrong.status)
        assertFalse(wrong.mayEnableAdas)

        val notEnough=ModelAxisAudit.inspect(points().take(11))
        assertEquals(ModelAxisAudit.Status.INSUFFICIENT_SAMPLES,
            notEnough.status)
        assertEquals(ModelAxisAudit.Status.INSUFFICIENT_COVERAGE,
            ModelAxisAudit.inspect(points().map {
                it.copy(modelVerticalRaw=0.0)
            }).status)
        val duplicates=points().toMutableList().apply {
            this[1] = this[0]
        }
        assertEquals(ModelAxisAudit.Status.INVALID_DATA,
            ModelAxisAudit.inspect(duplicates).status)
    }

    @Test fun ambiguousVerticalSignIsNotClaimedAsKnownModelAxis() {
        val result=ModelAxisAudit.inspect(points(upScale=.30))
        assertEquals(ModelAxisAudit.Status.AMBIGUOUS_AXES,result.status)
        assertFalse(result.mayEnableAdas)
    }

    private fun file(rows: List<ModelAxisAudit.Sample> = points()): String =
        (listOf("TTXAXIS01",
            "meta,unit-test,local-markers,${"a".repeat(64)}") +
            rows.map { s ->
                "point,${s.id},${s.modelForwardRaw},${s.modelLateralRaw}," +
                    "${s.modelVerticalRaw},${s.referenceForwardM}," +
                    "${s.referenceRightM},${s.referenceUpM}"
            }).joinToString("\n")

    private fun read(value: String) =
        ModelAxisFixture.read(ByteArrayInputStream(value.toByteArray()))

    @Test fun axisCsvCanBeAuditedButNeverTrustsClaimedModelHash() {
        val evidence=read(file())
        assertEquals("unit-test",evidence.datasetId)
        assertEquals("a".repeat(64),evidence.declaredModelSha256)
        assertEquals(ModelAxisAudit.Status.MATCH_UNVERIFIED,
            evidence.audit().status)
        assertFalse(evidence.audit().mayEnableAdas)
    }

    @Test fun axisCsvMalformedAndOversizedFilesAreRejected() {
        listOf(
            file().replace("TTXAXIS01","BAD-AXIS"),
            file().replace("local-markers","evil,extra"),
            file().replace("a".repeat(64),"0123"),
            file().replace("point,S101,","point,S101,NaN,"),
            file().lines().take(13).joinToString("\n"),
            "A".repeat(ModelAxisFixture.MAX_BYTES+1)
        ).forEach { broken ->
            assertNotEquals("Negative test fixture must actually be modified",
                file(), broken)
            try {
                read(broken)
                fail("Must reject malformed axis evidence")
            } catch (_: Exception) {}
        }
    }
}
