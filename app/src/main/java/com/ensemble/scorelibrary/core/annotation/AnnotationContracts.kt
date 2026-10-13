package com.ensemble.scorelibrary.core.annotation

data class NormalizedPdfPoint(
    val pageIndex: Int,
    val x: Float,
    val y: Float
) {
    init {
        require(pageIndex >= 0)
        require(x in 0f..1f)
        require(y in 0f..1f)
    }
}

data class NormalizedPdfRect(
    val pageIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(pageIndex >= 0)
        require(left in 0f..1f)
        require(top in 0f..1f)
        require(right in 0f..1f)
        require(bottom in 0f..1f)
        require(left <= right)
        require(top <= bottom)
    }
}
