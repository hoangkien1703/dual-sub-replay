package com.kienhoang.dualsubreplay.ui

/** Stands in for Android resources in JVM tests: the resource id, then any format arguments. */
internal object TestUiStrings : UiStrings {
    override fun get(
        id: Int,
        vararg formatArgs: Any,
    ): String = (listOf("#$id") + formatArgs.map(Any::toString)).joinToString(" ")
}
