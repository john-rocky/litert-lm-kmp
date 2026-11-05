package io.github.johnrocky.litertlm

internal actual fun environmentVariable(name: String): String? = System.getenv(name)
