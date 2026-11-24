// The shared avonioTV engine (channels, Xtream, M3U, guide), copied from wood29/avoniotv core/ by
// scripts/sync-avonio-core.sh. Don't edit the files here: change them in avoniotv and sync again.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    jvmToolchain(17)
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api("io.ktor:ktor-client-core:${libs.versions.ktor.get()}")
        }
    }
}
