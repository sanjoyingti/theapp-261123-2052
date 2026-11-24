package com.arin.bypass.model

/** Tiny holder so the pipeline can talk about "no findings" without a list literal. */
object Findings {
    val none: List<Finding> = emptyList()
}
