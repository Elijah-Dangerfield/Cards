plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    application
}

application {
    mainClass.set("com.dangerfield.cards.server.MainKt")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation("io.ktor:ktor-server-content-negotiation:3.3.3")
    implementation("io.ktor:ktor-server-cors:3.3.3")
    implementation("io.ktor:ktor-server-status-pages:3.3.3")
    implementation("io.ktor:ktor-server-call-logging:3.3.3")
    implementation("io.ktor:ktor-server-call-id:3.3.3")
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // DI — same pattern as the client: kotlin-inject + anvil
    implementation(libs.kotlin.inject.runtime.kmp)
    implementation(libs.anvil.runtime)
    implementation(libs.anvil.runtime.optional)
    ksp(libs.kotlin.inject.compiler.ksp)
    ksp(libs.anvil.compiler)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation("io.ktor:ktor-client-content-negotiation:3.3.3")
}

kotlin {
    jvmToolchain(17)
}
