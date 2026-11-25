import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val dynamicAppId: String = providers.gradleProperty("appId").orNull
    ?: System.getenv("LUMAXR_APP_ID")
    ?: "com.lumaxr.player"

val dynamicVersionCode: Int = (providers.gradleProperty("versionCode").orNull
    ?: System.getenv("LUMAXR_VERSION_CODE")
    ?: "100").toIntOrNull() ?: 100

val dynamicVersionName: String = providers.gradleProperty("versionName").orNull
    ?: System.getenv("LUMAXR_VERSION_NAME")
    ?: "1.0.0"

android {
    namespace = "com.lumaxr.player"
    compileSdk = 36

    defaultConfig {
        applicationId = dynamicAppId
        minSdk = 34
        targetSdk = 36
        versionCode = dynamicVersionCode
        versionName = dynamicVersionName

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "BUILD_APP_ID", "\"$dynamicAppId\"")
        buildConfigField("String", "TARGET_DEVICE_MODEL", "\"24074RPD2I\"")
        buildConfigField("String", "TARGET_DEVICE_CODENAME", "\"ruan_in\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("LUMAXR_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("LUMAXR_KEYSTORE_PASSWORD") ?: "lumaxr_ci"
                keyAlias = System.getenv("LUMAXR_KEYSTORE_ALIAS") ?: "lumaxr"
                keyPassword = System.getenv("LUMAXR_KEYSTORE_PASSWORD_KEY")
                    ?: System.getenv("LUMAXR_KEY_PASSWORD")
                    ?: "lumaxr_ci"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ks = System.getenv("LUMAXR_KEYSTORE_PATH")
            signingConfig = if (!ks.isNullOrBlank() && file(ks).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            excludes += listOf("**/armeabi-v7a/**", "**/x86/**", "**/x86_64/**")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2/",
                )
                it.testLogging {
                    events("passed", "skipped", "failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigationevent.compose)

    implementation(libs.mpv.android.lib)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.smbj)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.sardine.android) {
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
