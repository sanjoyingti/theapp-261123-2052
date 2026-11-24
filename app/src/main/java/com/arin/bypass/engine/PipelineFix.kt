package com.arin.bypass.engine

import com.arin.bypass.data.ProjectStore

/**
 * Marker object kept so older call sites still resolve their import. The real work
 * lives in [Pipeline]; this only describes which store the pipeline writes through.
 */
object PipelineFix {

    fun storeName(store: ProjectStore): String = store::class.java.simpleName

    fun describe(): String = "pipeline fix marker"
}
