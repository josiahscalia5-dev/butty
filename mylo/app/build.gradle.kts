import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}
/**
 * Mylo Shield service settings come from the developer's machine or CI, never from the repository:
 * `-Pmylo.shield.apiBaseUrl=…`, the same keys in local.properties, or MYLO_SHIELD_API_BASE_URL /
 * MYLO_SHIELD_DEV_TOKEN. Without a base URL the app builds with Shield shown as "Server setup required".
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun shieldSetting(property: String, environment: String): String =
    (project.findProperty(property) as String?) ?: localProperties.getProperty(property) ?: System.getenv(environment) ?: ""
fun buildConfigString(value: String): String {
    require(value.none { it == '"' || it == '\\' || it.isISOControl() }) { "Unsupported character in a Mylo Shield setting" }
    return "\"$value\""
}
val shieldApiBaseUrl = shieldSetting("mylo.shield.apiBaseUrl", "MYLO_SHIELD_API_BASE_URL").trim()
val shieldDevToken = shieldSetting("mylo.shield.devToken", "MYLO_SHIELD_DEV_TOKEN").trim()

android {
    namespace = "com.mylo.browser"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mylo.browser"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SHIELD_API_BASE_URL", buildConfigString(shieldApiBaseUrl))
    }
    buildTypes {
        // A development access token is only ever compiled into debug builds; release builds must use a
        // per-user sign-in flow (see docs/shield/BACKEND_API.md).
        debug { buildConfigField("String", "SHIELD_DEV_TOKEN", buildConfigString(shieldDevToken)) }
        release { buildConfigField("String", "SHIELD_DEV_TOKEN", "\"\"") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Mylo Shield: the official WireGuard Android tunnel library (wireguard-go, Apache-2.0).
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json for JVM tests (the Android stub returns empty values).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
