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
