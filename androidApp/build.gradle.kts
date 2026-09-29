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
  }

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
}

kotlin {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
