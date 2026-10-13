package com.ensemble.scorelibrary.pdf

import com.ensemble.scorelibrary.annotation.*

class TestPerformanceAnnotations : AnnotationStore {
    var locked = true
    var failurePage: Int? = null
    var barrier: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val loaded = mutableListOf<AnnotationScope>()
    override suspend fun load(scope: AnnotationScope): AnnotationData {
        barrier?.await(); check(scope.page != failurePage) { "批注损坏" }; loaded += scope
        return AnnotationData(emptyList(), emptyList(), emptyList(), 0, 0, false, scope.item, locked)
    }
    override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) = error("Not used")
    override suspend fun publish(scope: AnnotationScope) = error("Not used")
    override suspend fun freeze(scope: AnnotationScope) = error("Not used")
}
