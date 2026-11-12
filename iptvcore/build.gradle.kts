// Nuvio Plus IPTV core: the clean Live TV engine for iPhone, iPad and (later) Apple TV.
// Its own module with its own licence, so an IPTV-only app can be split off later.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation("io.ktor:ktor-client-core:${libs.versions.ktor.get()}")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
    }
}
