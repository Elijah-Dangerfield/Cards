# R8 rules for the release build.
#
# Play flags an app under 25% obfuscation as "below our threshold" with a
# Feb 2027 deadline, and shrinking/optimisation are a real size and startup win
# besides. Nothing here was inherited — the app shipped unminified until now, so
# every rule below is a deliberate decision rather than a copied default.
#
# The rule of thumb: R8 breaks whatever is found by *name at runtime* rather
# than referenced in code. In this app that is three things — serialization,
# type-safe navigation routes, and the DI graph's generated code.

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# Serializers are resolved through a generated `Companion.serializer()` that
# nothing calls directly, so R8 sees them as dead. Losing one does not fail the
# build; it throws at runtime the first time that model crosses the wire.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dangerfield.cards.**$$serializer { *; }
-keepclassmembers class com.dangerfield.cards.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Enum constants are matched by name during deserialization.
-keepclassmembers enum com.dangerfield.cards.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Every @Serializable model, with its fields. Names in the JSON are the field
# names unless @SerialName says otherwise, so renaming silently changes the
# wire format — which the server would reject and no compile step would catch.
-keep @kotlinx.serialization.Serializable class com.dangerfield.cards.** { *; }

# ---------------------------------------------------------------------------
# Type-safe navigation
# ---------------------------------------------------------------------------
# Routes are @Serializable classes that androidx.navigation resolves by type.
# Covered by the rule above, kept explicitly because this is the failure that
# would be hardest to attribute: navigation stops working with an argument
# error rather than a missing-class error.
-keep class com.dangerfield.cards.libraries.navigation.** { *; }
-keep class * extends com.dangerfield.cards.libraries.navigation.Route { *; }

# ---------------------------------------------------------------------------
# DI (kotlin-inject-anvil)
# ---------------------------------------------------------------------------
# The graph is generated at compile time, so it needs no reflection help. The
# generated component is referenced through `::class.create`, which R8 can
# follow — but the entry point is worth pinning so a mistake here is loud.
-keep class com.dangerfield.cards.**AppComponent* { *; }

# ---------------------------------------------------------------------------
# Wiretap — present in debug, swapped for a no-op in release
# ---------------------------------------------------------------------------
# `releaseImplementation(wiretap.ktor.noop)` covers the Ktor plugins but not the
# console launcher, so `launchNetworkInspector()` compiles against a class that
# is not in a release APK. Unminified nobody noticed: the call is unreachable
# because the shake menu only offers the inspector when `BuildInfo.isDebug`.
# R8 is right to flag it and this says "yes, on purpose".
-dontwarn dev.skymansandy.wiretap.**

# ---------------------------------------------------------------------------
# Readable crash output
# ---------------------------------------------------------------------------
# Keep exception *class names*. Obfuscated, every Sentry issue and every ANR
# report arrives titled `a.b.c` and has to be un-mangled before it can even be
# triaged. Throwable names are a rounding error in APK size and the difference
# between a readable inbox and an unreadable one.
-keepnames class * extends java.lang.Throwable

# Logging is deliberately NOT stripped. The default `proguard-android-optimize`
# file does not remove `android.util.Log` calls, and nothing here adds an
# `-assumenosideeffects` rule to do it. The app logs through Kermit/KLog anyway,
# which feeds the Grafana pipe — stripping it would blind production telemetry,
# not just logcat. Do not add a log-stripping rule without reading
# docs/wiki/observability.md first.

# ---------------------------------------------------------------------------
# Third parties that resolve by name
# ---------------------------------------------------------------------------
# Ktor, Supabase, Sentry and Room ship their own consumer rules; these cover
# the reflective edges those rules leave to the app, plus warnings from
# JVM-only branches that are never reached on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn kotlinx.coroutines.debug.**

# OkHttp / Okio internals referenced only on other platforms.
-dontwarn okhttp3.internal.**
-dontwarn okio.**

# Keep exception class names in stack traces. Without this every Sentry report
# and every ANR trace comes back as obfuscated soup, which would cost more in
# triage time than obfuscation saves in size.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
