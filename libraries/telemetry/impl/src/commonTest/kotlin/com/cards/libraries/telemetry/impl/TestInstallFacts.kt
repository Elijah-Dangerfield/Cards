package com.dangerfield.cards.libraries.telemetry.impl

/** A plain retail Play install — the shape most tests want in the background. */
internal val RetailInstallFacts = InstallFacts(
    source = InstallSource.PlayStore,
    isEmulator = false,
    isRooted = false,
    deviceClass = DeviceClass.High,
    osVersion = "15",
)
