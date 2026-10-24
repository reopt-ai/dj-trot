plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 🔑 백엔드 주소 — DJ 트롯 서버(`/api/v1/dj-trot`). 다른 주소는 -Pdjtrot.api로 바꾼다.
//    에뮬레이터에서 맥의 로컬 서버: -Pdjtrot.api=http://10.0.2.2:8787/api/v1/dj-trot
//    목 서버(tools/mock_djtrot.py): -Pdjtrot.api=http://10.0.2.2:8790/api/v1/dj-trot
val djTrotApi = providers.gradleProperty("djtrot.api").orElse("https://miniapp.reopt.ai/api/v1/dj-trot")
// 🔑 Pleos Playground 프로젝트의 Client ID/Secret — TTS 서버(하이브리드) 모드에 쓴다. 저장소에 넣지 말고 ~/.gradle/gradle.properties에 둔다.
//    없으면 온디바이스 모드로만 말한다(에뮬레이터 온디바이스 엔진은 ON_DEVICE_ENGINE_ERROR로 실패한다).
val pleosClientId = providers.gradleProperty("djtrot.pleosClientId").orElse("")
val pleosClientSecret = providers.gradleProperty("djtrot.pleosClientSecret").orElse("")

android {
    namespace = "ai.reopt.djtrot"
    compileSdk = 37
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.reopt.djtrot"
        // 🔴 Pleos Connect 요구: minSdk 28 이상. 에뮬레이터·실차는 API 34.
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DJ_TROT_API", "\"${djTrotApi.get()}\"")
        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${pleosClientId.get()}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${pleosClientSecret.get()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.tts)

    testImplementation(libs.junit)
}
