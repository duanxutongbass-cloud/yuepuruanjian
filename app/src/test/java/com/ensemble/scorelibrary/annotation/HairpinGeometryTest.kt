package com.ensemble.scorelibrary.annotation

import org.junit.Assert.*
import org.junit.Test

class HairpinGeometryTest {
    @Test fun tapUsesChosenSpanAndStaysOnPageAtTheRightEdge() {
        val placed=HairpinGeometry.placement(listOf(InkPoint(.95f,.4f)),.3f)
        assertEquals(.7f,placed.first().x,.0001f);assertEquals(1f,placed.last().x)
        assertEquals(.4f,placed.first().y);assertEquals(.4f,placed.last().y)
    }
    @Test fun reverseDraggingKeepsDirectionAndUsesHorizontalLength() {
        assertEquals(listOf(InkPoint(.2f,.4f),InkPoint(.8f,.4f)),
            HairpinGeometry.placement(listOf(InkPoint(.8f,.4f),InkPoint(.2f,.5f)),.1f))
    }
    @Test fun legacyOnePointHairpinCanBeResizedWithoutChangingIdentityOrColor() {
        val old=InkMark(kind=MarkKind.SYMBOL,symbol=">",points=listOf(InkPoint(.3f,.5f)))
        val resized=HairpinGeometry.resize(old,.4f,1.5f)
        assertEquals(old.id,resized.id);assertEquals(old.color,resized.color);assertEquals(old.width,resized.width)
        assertEquals(.4f,resized.points.last().x-resized.points.first().x,.00001f)
        assertEquals(1.5f,resized.symbolScale)
    }
}
