// androidx.collection built from the official AndroidX sources (fetched by setup.sh into build/androidx).
plugins { kotlin("multiplatform") }
val src = rootDir.resolve("build/androidx/collection/collection/src").path
kotlin {
    jvmToolchain(21)
    jvm { withJava() }
    sourceSets {
        commonMain { kotlin.srcDir("$src/commonMain/kotlin"); kotlin.srcDir("stubs") }
        jvmMain { kotlin.srcDir("$src/jvmMain/kotlin"); dependencies { implementation("org.jspecify:jspecify:1.0.0") } }
    }
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes"); optIn.add("kotlin.contracts.ExperimentalContracts") }
}
java.sourceSets["main"].java.srcDir("$src/jvmMain/java")
