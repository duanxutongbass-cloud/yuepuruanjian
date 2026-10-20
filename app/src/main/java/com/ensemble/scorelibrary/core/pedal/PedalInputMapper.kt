package com.ensemble.scorelibrary.core.pedal

import android.view.KeyEvent
import com.ensemble.scorelibrary.core.model.PedalAction

interface PedalInputMapper {
    fun map(event: KeyEvent): PedalAction
}

class DefaultPedalInputMapper : PedalInputMapper {
    override fun map(event: KeyEvent): PedalAction {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 || event.isCanceled) return PedalAction.NO_OP
        return defaultPedalKeys()[event.keyCode] ?: PedalAction.NO_OP
    }
}
