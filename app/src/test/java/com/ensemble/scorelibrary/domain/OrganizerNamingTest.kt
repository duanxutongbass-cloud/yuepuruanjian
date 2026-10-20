package com.ensemble.scorelibrary.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrganizerNamingTest {
    private val rules = OrganizerRules.read(ApplicationProvider.getApplicationContext<Context>().assets
        .open("score-organizer-rules.json").bufferedReader().use { it.readText() })
    @Test fun desktopOldAndNewFilenameRulesHaveSameDictionariesAndTitle() {
        val old = parseOrganizerFilename("0101天空之城.pdf", rules)
        assertNull(old.error); assertEquals("天空之城", old.title); assertEquals("第一小提琴", old.instrumentName)
        assertEquals("久石让", old.categoryName); assertNull(old.sequence)
        val fresh = parseOrganizerFilename("0201007天空之城.PDF", rules)
        assertNull(fresh.error); assertEquals("第二小提琴", fresh.instrumentName); assertEquals("007", fresh.sequence)
        assertEquals("天空之城", fresh.title)
        assertEquals("总谱", parseOrganizerFilename("0808001曲名.pdf", rules).instrumentName)
        assertEquals("杂项", parseOrganizerFilename("0808001曲名.pdf", rules).categoryName)
        assertEquals("总谱+分谱", rules.instruments["09"])
        assertEquals("周宏德", rules.categories["07"])
    }
    @Test fun badNewNumberOrDictionaryNeverFallsBackToOldFormat() {
        assertNotNull(parseOrganizerFilename("0101000曲名.pdf", rules).error)
        assertNotNull(parseOrganizerFilename("9901001曲名.pdf", rules).error)
        assertNotNull(parseOrganizerFilename("0199001曲名.pdf", rules).error)
        assertNotNull(parseOrganizerFilename("曲名.pdf", rules).error)
        assertNotNull(parseOrganizerFilename("0101001曲名.txt", rules).error)
        assertEquals("曲名（第二版）", parseOrganizerFilename("0101002曲名（第二版）.pdf", rules).title)
        assertEquals("HANDCLAP-小提琴_1", parseOrganizerFilename("0101HANDCLAP-小提琴_1.pdf", rules).title)
    }
    @Test fun exportedCustomDictionaryChangesMappingAndMalformedRulesAreRejected() {
        val custom = OrganizerRules.read("""{"format":"score-organizer-naming","version":1,"sequenceDigits":3,"instruments":{"21":"长笛"},"categories":{"37":"自定义"}}""")
        val result = parseOrganizerFilename("2137001作品.pdf", custom)
        assertNull(result.error); assertEquals("长笛", result.instrumentName); assertEquals("自定义", result.categoryName)
        assertNotNull(parseOrganizerFilename("0101001作品.pdf", custom).error)
        assertThrows(IllegalArgumentException::class.java) { OrganizerRules.read("""{"format":"score-organizer-naming","version":1,"sequenceDigits":4,"instruments":{"01":"A"},"categories":{"01":"B"}}""") }
    }
}
