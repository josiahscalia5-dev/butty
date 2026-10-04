// Desktop render of the production Home/search composables (see README.md). Not part of the app build.
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    kotlin("multiplatform") version "2.0.21" apply false
    application
}
kotlin { jvmToolchain(21) }
sourceSets.main { kotlin.srcDir("build/gen/kotlin") }
val compose = "1.7.3"
configurations.all {
    // These are published only on Google Maven. collection is built from source; the rest are never loaded offscreen.
    exclude(group = "androidx.collection"); exclude(group = "androidx.annotation")
    exclude(group = "androidx.lifecycle"); exclude(group = "androidx.arch.core")
}
dependencies {
    implementation(project(":collection"))
    implementation("org.jetbrains.compose.ui:ui-desktop:$compose")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:$compose")
    implementation("org.jetbrains.compose.material3:material3-desktop:$compose")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:$compose")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.18")
}
application { mainClass.set("RenderKt") }
tasks.named<JavaExec>("run") {
    systemProperty("mylo.res", rootDir.resolve("../../app/src/main/res").canonicalPath)
    args(project.findProperty("out") ?: "build/renders", project.findProperty("only") ?: "")
}
