package com.ensemble.scorelibrary.annotation

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InkPressureTest {
    private val points=listOf(InkPoint(.2f,.3f),InkPoint(.8f,.3f))
    private fun reject(body:()->Unit) { try { body();fail("Invalid pressure accepted") } catch(_:IllegalArgumentException) {} }
    @Test fun pressureSurvivesCodecMoveResizeAndLegacyPayloadRemainsUnchanged() {
        val mark=InkMark(id="pressure",kind=MarkKind.PEN,points=points,pressures=listOf(.1f,1f))
        assertEquals(mark,InkCodec.decode(InkCodec.encode(listOf(mark))).single())
        val moved=InkGeometry.move(listOf(mark),setOf(mark.id),.1f,.2f).single()
        assertEquals(mark.pressures,moved.pressures)
        assertEquals(.003f*.52f,InkPressure.width(mark,0),.000001f)
        assertEquals(InkPressure.width(mark,1)*2,InkPressure.width(mark.copy(width=mark.width*2),1),.000001f)
        val legacy=mark.copy(pressures=null)
        val raw=InkCodec.encode(listOf(legacy));assertFalse(raw.contains("pressures"))
        assertEquals(legacy,InkCodec.decode(raw).single())
    }
    @Test fun malformedPressureCannotPartiallyLoadOrCrossIntoNonPenMarks() {
        reject { InkMark(kind=MarkKind.PEN,points=points,pressures=listOf(.2f)) }
        reject { InkMark(kind=MarkKind.HIGHLIGHT,points=points,pressures=listOf(.2f,.3f)) }
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,-.01f,1.01f))reject {
            InkMark(kind=MarkKind.PEN,points=points,pressures=listOf(.2f,value))
        }
        val raw=InkCodec.encode(listOf(InkMark(kind=MarkKind.PEN,points=points,pressures=listOf(.2f,.3f))))
        for(value in listOf("[true,0.3]","[0.2]","[0.2,1.001]","null","[\"0.2\",0.3]")) {
            try { InkCodec.decode(raw.replace(Regex("\\\"pressures\\\":\\[[^]]*]"),"\"pressures\":$value"));fail("Malformed payload accepted") }
            catch(e:AssertionError) { throw e } catch(_:Exception) {}
        }
    }
    @Test fun calibrationInvalidSamplesAndZeroOnLiftAreBoundedAndPredictable() {
        assertEquals(.5f,InkPressure.sample(Float.NaN));assertEquals(.7f,InkPressure.sample(0f,.7f))
        assertEquals(.7f,InkPressure.sample(-1f,.7f));assertEquals(1f,InkPressure.sample(2f))
        assertEquals(.2f,InkPressure.sample(.2f,.7f))
    }
    @Test fun legacyBaselineCanRecoverOnlyMissingPressureAtAnOtherwiseIdenticalRevision() {
        val original=InkMark(kind=MarkKind.PEN,points=points)
        val enriched=original.copy(pressures=listOf(.1f,.9f))
        val baseline=InkCodec.encode(listOf(original));val head=InkCodec.encode(listOf(enriched))
        assertTrue(InkCodec.pressureEnrichment(baseline,head))
        for(changed in listOf(enriched.copy(id="foreign"),enriched.copy(color=0xff0000ff),enriched.copy(width=.006f),
            enriched.copy(points=points.map { it.copy(y=it.y+.1f) })))assertFalse(InkCodec.pressureEnrichment(baseline,InkCodec.encode(listOf(changed))))
        assertFalse(InkCodec.pressureEnrichment(head,InkCodec.encode(listOf(enriched.copy(pressures=listOf(.2f,.8f))))))
        assertFalse(InkCodec.pressureEnrichment(head,baseline));assertFalse(InkCodec.pressureEnrichment("[]",head))
    }
    @Test fun erasingIncludesThickPressureEdgesAndKeepsUnrelatedInk() {
        val thick=InkMark(kind=MarkKind.PEN,points=points,width=.02f,pressures=listOf(1f,1f))
        val keep=thick.copy(id="keep",points=points.map { it.copy(y=.8f) })
        assertEquals(listOf(keep),InkGeometry.erase(listOf(thick,keep),listOf(InkPoint(.5f,.32f)),.005f))
    }
}
