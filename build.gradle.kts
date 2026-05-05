// Top-level build file. Configures plugins for all subprojects;
// keeps each module's build.gradle.kts focused on module-specific bits.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
