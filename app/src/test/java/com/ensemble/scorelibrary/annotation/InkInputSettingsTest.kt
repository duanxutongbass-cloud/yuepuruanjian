package com.ensemble.scorelibrary.annotation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InkInputSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() { context.getSharedPreferences("ink-input", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun existingUsersKeepTouchUntilTheyChooseAndNewInstancesKeepChoice() {
        assertEquals(InkInputMode.TOUCH, InkInputSettings(context).load())
        InkInputSettings(context).save(InkInputMode.PEN)
        assertEquals(InkInputMode.PEN, InkInputSettings(context).load())
        InkInputSettings(context).save(InkInputMode.TOUCH)
        assertEquals(InkInputMode.TOUCH, InkInputSettings(context).load())
    }
    @Test fun unknownFutureModeUsesVisiblePenModeInsteadOfAcceptingPalms() {
        context.getSharedPreferences("ink-input", Context.MODE_PRIVATE).edit().putString("mode", "future").commit()
        assertEquals(InkInputMode.PEN, InkInputSettings(context).load())
    }
    @Test fun wrongPreferenceTypeDoesNotCrashOrEnableFingerInk() {
        context.getSharedPreferences("ink-input", Context.MODE_PRIVATE).edit().putInt("mode", 3).commit()
        assertEquals(InkInputMode.PEN, InkInputSettings(context).load())
    }
    @Test fun pressureAndBarrelAreExplicitPersistentOptInsAndCorruptionFallsBackOff() {
        val settings=InkInputSettings(context)
        assertFalse(settings.pressure());assertEquals(InkSideButton.OFF,settings.sideButton())
        settings.pressure(true);settings.sideButton(InkSideButton.SECONDARY)
        assertTrue(InkInputSettings(context).pressure());assertEquals(InkSideButton.SECONDARY,InkInputSettings(context).sideButton())
        context.getSharedPreferences("ink-input",Context.MODE_PRIVATE).edit().putString("pressure","future").putInt("side-button",1).commit()
        assertFalse(settings.pressure());assertEquals(InkSideButton.OFF,settings.sideButton())
    }
}
