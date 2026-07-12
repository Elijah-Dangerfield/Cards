package com.dangerfield.cards.libraries.telemetry.impl

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory

internal actual fun testTelemetryBufferDirectory(name: String): Path =
    (NSTemporaryDirectory() + name).toPath()
