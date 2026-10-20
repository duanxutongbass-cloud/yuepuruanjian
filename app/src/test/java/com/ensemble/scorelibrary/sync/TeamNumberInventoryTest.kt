package com.ensemble.scorelibrary.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TeamNumberInventoryTest {
    private fun reply(used:List<Int> = listOf(1,3,999))=JSONObject().put("protocolVersion",1).put("revision",7)
        .put("maximumSequence","999").put("occupiedSequences",JSONArray(used.map { it.toString().padStart(3,'0') }))
        .put("availableCount",999-used.size).put("firstFreeSequence",(1..999).firstOrNull { it !in used }?.toString()?.padStart(3,'0') ?: JSONObject.NULL)
        .put("pendingUploads",2)
    @Test fun validReadOnlyInventoryKeepsReservedNumbersAndReportsFirstHole() {
        val inventory=TeamNumberInventory.parse(reply())
        assertEquals(setOf(1L,3L,999L),inventory.occupied);assertEquals(2L,inventory.firstFree)
        assertEquals(996L,inventory.availableCount);assertEquals(7L,inventory.revision)
        val full=TeamNumberInventory.parse(reply((1..999).toList()))
        assertNull(full.firstFree);assertEquals(0L,full.availableCount)
    }
    @Test fun malformedOrContradictoryInventoryCannotEnableGapAllocation() {
        val invalid=listOf(reply().put("firstFreeSequence","001"),reply().put("availableCount",999),
            reply().put("maximumSequence","1000"),reply().put("revision",1.5),reply().put("pendingUploads",-1),
            reply().put("occupiedSequences",JSONArray(listOf("001","001","999"))),
            reply().put("occupiedSequences",JSONArray(listOf("003","001","999"))),
            reply().put("occupiedSequences",JSONArray(listOf("000","003","999"))),
            reply().put("occupiedSequences",JSONArray(listOf("1","003","999"))))
        invalid.forEach { root ->
            try { TeamNumberInventory.parse(root);fail("Untrusted inventory must fail closed") }
            catch(_:IllegalArgumentException) { }
        }
    }
}
