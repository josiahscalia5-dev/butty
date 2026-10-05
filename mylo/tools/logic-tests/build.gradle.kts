// Runs Mylo's pure-Kotlin logic and its unit tests on the JVM, without the Android SDK (see README.md).
// The files are the app's own sources; nothing is copied.
plugins { kotlin("jvm") version "2.0.21" }
kotlin { jvmToolchain(21) }
val app = rootDir.resolve("../../app/src")
/** Pure-Kotlin sources (no android.* imports) and their tests. */
val logic = listOf(
    "web/WebPolicy.kt", "web/TrackerBlocker.kt", "web/TrackerList.kt",
)
val logicTests = listOf(
    "web/WebPolicyTest.kt", "web/TrackerBlockerTest.kt",
)
sourceSets {
    main { kotlin.setSrcDirs(emptyList<String>()); kotlin.srcDir(layout.buildDirectory.dir("logic/main")) }
    test { kotlin.setSrcDirs(emptyList<String>()); kotlin.srcDir(layout.buildDirectory.dir("logic/test")) }
}
val copyLogic by tasks.registering(Sync::class) {
    from(app.resolve("main/java/com/mylo/browser")) { include(logic) }
    into(layout.buildDirectory.dir("logic/main/com/mylo/browser"))
}
val copyTests by tasks.registering(Sync::class) {
    from(app.resolve("test/java/com/mylo/browser")) { include(logicTests) }
    into(layout.buildDirectory.dir("logic/test/com/mylo/browser"))
}
tasks.named("compileKotlin") { dependsOn(copyLogic) }
tasks.named("compileTestKotlin") { dependsOn(copyTests) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.json:json:20240303")
}
tasks.test { testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = false } }
