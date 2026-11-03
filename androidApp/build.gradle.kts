plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
}

android {
  namespace = "com.bbpillow.haku"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.bbpillow.haku"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.0.1"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // BuildConfig.DEBUG gates logs that carry personal data (presence times, event ids) out of release builds
  buildFeatures { buildConfig = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(project(":core"))
  implementation(project(":ui"))
  implementation(compose.material3)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.litertlm.android)
  implementation(libs.androidx.sqlite.bundled)
  implementation(libs.sqlcipher.android) // D11: haku.db at rest
  implementation(libs.play.services.location)
  implementation(libs.okio)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.work.runtime)
  implementation(libs.play.billing.ktx)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  implementation(libs.kotlinx.serialization.json)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}
