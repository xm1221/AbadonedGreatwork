import dev.panuszewski.gradle.pluginMarker

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven { url = uri("https://maven.architectury.dev/") }
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://maven.minecraftforge.net/") }
    maven { url = uri("https://maven.blamejared.com/") }
    flatDir { dirs("libs") }
}

dependencies {
    // plugins used in convention plugins
    implementation(pluginMarker(libs.plugins.kotlin.jvm))
    implementation(pluginMarker(libs.plugins.architectury.asProvider()))
    implementation(pluginMarker(libs.plugins.architectury.loom))
    implementation(pluginMarker(libs.plugins.shadow))
    implementation(pluginMarker(libs.plugins.modPublish))
    implementation(pluginMarker(libs.plugins.pkJson5))
}

java {
    toolchain {
        // 构建逻辑本身用 21（Loom 1.11 的 unpick 3.0 要求 21）；mod 产物仍是 libs.versions.java = 17
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get())
    }
}
