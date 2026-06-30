package com.ai.assistance.operit.ui.features.chat.components.lazy

internal inline fun checkPrecondition(value: Boolean, lazyMessage: () -> String) {
    if (!value) {
        throw IllegalStateException(lazyMessage())
    }
}

internal inline fun requirePrecondition(value: Boolean, lazyMessage: () -> String) {
    if (!value) {
        throw IllegalArgumentException(lazyMessage())
    }
}

internal inline fun <T : Any> requirePreconditionNotNull(
    value: T?,
    lazyMessage: () -> String
): T {
    if (value == null) {
        throw IllegalArgumentException(lazyMessage())
    }
    return value
}

internal fun throwIndexOutOfBoundsException(message: String): Nothing {
    throw IndexOutOfBoundsException(message)
}
