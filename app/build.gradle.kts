import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.net.HttpURLConnection
import java.net.URI

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val isReleaseOrBundleTask = gradle.startParameter.taskNames.any {
  it.contains("Release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
}

android {
  namespace = "com.hollow.vpn"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.hollow.vpn.lcwsbx"
    minSdk = 24
    targetSdk = 36
    // Release artifacts are split like v2rayNG: 32-bit ARM, 64-bit ARM, and universal ARM.
    // Debug builds keep all ABIs (including x86_64) and output standard app-debug.apk for preview.
    if (isReleaseOrBundleTask) {
      ndk {
        abiFilters += listOf("armeabi-v7a", "arm64-v8a")
      }
    }
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  splits {
    abi {
      isEnable = isReleaseOrBundleTask
      reset()
      include("armeabi-v7a", "arm64-v8a")
      isUniversalApk = true
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.maxParallelForks = 1
        it.systemProperty("java.awt.headless", "true")
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

val libV2rayFile = layout.projectDirectory.file("libs/libv2ray.aar").asFile
if (!libV2rayFile.exists() || libV2rayFile.length() < 1024L) {
  libV2rayFile.parentFile.mkdirs()
  val releaseUrl = "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.31/libv2ray.aar"
  logger.lifecycle("Downloading libv2ray.aar (v26.10.31) from $releaseUrl ...")
  var currentUrl = releaseUrl
  var connection: HttpURLConnection
  var hops = 0
  while (true) {
    connection = (URI(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
      instanceFollowRedirects = false
      connectTimeout = 15_000
      readTimeout = 60_000
      setRequestProperty("User-Agent", "HollowVPN-Gradle/1.0")
    }
    val code = connection.responseCode
    if (code in 300..399 && hops < 6) {
      val next = connection.getHeaderField("Location") ?: error("Redirect without Location header for $currentUrl")
      connection.disconnect()
      currentUrl = URI(currentUrl).resolve(next).toString()
      hops++
    } else {
      break
    }
  }
  if (connection.responseCode !in 200..299) {
    val msg = "Failed to download libv2ray.aar (HTTP ${connection.responseCode})"
    connection.disconnect()
    error(msg)
  }
  val tempFile = file("${libV2rayFile.absolutePath}.tmp")
  connection.inputStream.use { input ->
    tempFile.outputStream().use { output -> input.copyTo(output) }
  }
  connection.disconnect()
  if (!tempFile.renameTo(libV2rayFile)) {
    tempFile.copyTo(libV2rayFile, overwrite = true)
    tempFile.delete()
  }
}

dependencies {
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.zxing.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
