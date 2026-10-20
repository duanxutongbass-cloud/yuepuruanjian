package com.ensemble.scorelibrary.annotation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class InkSideButton(val label:String,val mask:Int) {
    OFF("不使用侧键",0), PRIMARY("按住主侧键擦除",android.view.MotionEvent.BUTTON_STYLUS_PRIMARY),
    SECONDARY("按住副侧键擦除",android.view.MotionEvent.BUTTON_STYLUS_SECONDARY)
}

/** Device input preference only: contains no account, PDF or ink data. */
@Singleton
class InkInputSettings @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("ink-input", Context.MODE_PRIVATE)
    fun load(): InkInputMode {
        val value = try { preferences.getString("mode", null) } catch (_: ClassCastException) { return InkInputMode.PEN }
        return if (value == null) InkInputMode.TOUCH else InkInputMode.entries.firstOrNull { it.name == value } ?: InkInputMode.PEN
    }
    fun save(mode: InkInputMode) { preferences.edit().putString("mode", mode.name).apply() }
    fun pressure()=try { preferences.getBoolean("pressure",false) } catch(_:ClassCastException) { false }
    fun sideButton():InkSideButton=try { InkSideButton.entries.firstOrNull { it.name==preferences.getString("side-button",null) } ?: InkSideButton.OFF }
        catch(_:ClassCastException) { InkSideButton.OFF }
    fun pressure(enabled:Boolean) { preferences.edit().putBoolean("pressure",enabled).apply() }
    fun sideButton(button:InkSideButton) { preferences.edit().putString("side-button",button.name).apply() }
}
