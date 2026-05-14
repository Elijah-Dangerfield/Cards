plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    mainClass.set("com.dangerfield.cards.server.MainKt")
}

dependencies {
    implementation(projects.libraries.appconfig)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation("io.ktor:ktor-server-content-negotiation:3.3.3")
    implementation("io.ktor:ktor-server-cors:3.3.3")
    implementation("io.ktor:ktor-server-status-pages:3.3.3")
    implementation("io.ktor:ktor-server-call-logging:3.3.3")
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

kotlin {
    jvmToolchain(17)
}
