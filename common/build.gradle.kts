

plugins {
    id("abadoned_greatwork.minecraft")
}

architectury {
    common("fabric", "forge")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(kotlin("reflect"))


    // We depend on fabric loader here to use the fabric @Environment annotations and get the mixin dependencies
    // Do NOT use other classes from fabric loader
    modImplementation(libs.fabric.loader)
    modApi(libs.architectury)

    modApi(libs.hexcasting.common)

    // IoticBlocks：只借用它的 API（把方块登记成 iota 读写目标），运行时由各平台各自的 jar 提供
    modCompileOnly(libs.ioticblocks.fabric)

    modApi(libs.clothConfig.common)

    //modImplementation(libs.moreiotas)

    libs.mixinExtras.common.also {
        implementation(it)
        annotationProcessor(it)
    }

    fileTree(baseDir = "libs").also { it.include("fabric") }
}
