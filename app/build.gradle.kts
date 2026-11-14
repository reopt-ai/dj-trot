plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 🔑 백엔드 주소 — DJ 트롯 서버(`/api/v1/dj-trot`). 다른 주소는 -Pdjtrot.api로 바꾼다.
//    에뮬레이터에서 맥의 로컬 서버: -Pdjtrot.api=http://10.0.2.2:8787/api/v1/dj-trot
//    목 서버(tools/mock_djtrot.py): -Pdjtrot.api=http://10.0.2.2:8790/api/v1/dj-trot
val djTrotApi = providers.gradleProperty("djtrot.api").orElse("https://miniapp.reopt.ai/api/v1/dj-trot")
// 🔑 Pleos Playground 프로젝트의 Client ID/Secret — TTS·STT 서버(하이브리드) 모드에 쓴다. 저장소에 넣지 말고 ~/.gradle/gradle.properties에 둔다.
//    없으면 온디바이스 모드로만 말한다(에뮬레이터 온디바이스 엔진은 ON_DEVICE_ENGINE_ERROR로 실패한다).
// 🔑 시연 탭(화면에서 길 위의 상황을 버튼으로 흉내 낸다) — 공모전·전시용. 끄려면 -Pdjtrot.demo=false
val djTrotDemo = providers.gradleProperty("djtrot.demo").orElse("true")
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
        buildConfigField("boolean", "DEMO", if (djTrotDemo.get().toBoolean()) "true" else "false")
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

    lint {
        // 경고도 실패로 본다. 일부러 남기는 항목은 lint.xml에 이유와 함께 적는다.
        warningsAsErrors = true
        lintConfig = file("lint.xml")
    }
}

kotlin {
    compilerOptions {
        // CI에서는 컴파일 경고도 실패로 본다. 로컬에서는 경고로만 보여 작업 흐름을 끊지 않는다.
        allWarningsAsErrors = providers.environmentVariable("CI").isPresent
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
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    testImplementation(libs.junit)
}
