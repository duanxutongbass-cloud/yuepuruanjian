package com.ensemble.scorelibrary.annotation

/** Bounded device pressure; absent/invalid contact samples retain the last valid sample. */
internal object InkPressure {
    fun sample(raw:Float,previous:Float?=null):Float =
        if(raw.isFinite() && raw>0f)raw.coerceAtMost(1f) else previous ?: .5f
    fun factor(pressure:Float)=.4f+1.2f*pressure
    fun width(mark:InkMark,index:Int)=mark.width*(mark.pressures?.get(index)?.let(::factor) ?: 1f)
}
