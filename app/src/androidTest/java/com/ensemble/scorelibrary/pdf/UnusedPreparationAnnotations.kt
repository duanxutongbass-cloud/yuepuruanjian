package com.ensemble.scorelibrary.pdf

import com.ensemble.scorelibrary.annotation.*

// Geometry-only reader tests must fail if they unexpectedly invoke a fake preparation path.
class UnusedPreparationAnnotations : AnnotationStore {
    override suspend fun load(scope: AnnotationScope): AnnotationData = error("Preparation requires the real Room fixture")
    override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) = error("Not used")
    override suspend fun publish(scope: AnnotationScope) = error("Not used")
    override suspend fun freeze(scope: AnnotationScope) = error("Not used")
}
