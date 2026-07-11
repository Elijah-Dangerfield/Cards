package com.dangerfield.cards.libraries.telemetry.impl

import okio.Path
import okio.Path.Companion.toPath

internal actual fun testTelemetryBufferDirectory(name: String): Path =
    (System.getProperty("java.io.tmpdir")!! + "/" + name).toPath()
