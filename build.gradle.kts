plugins {
    alias(libs.plugins.android.application) apply false
    // 🔑 AGP 9는 Kotlin을 내장한다. KGP를 클래스패스에 올려 Compose·직렬화 컴파일러 플러그인과 버전을 맞춘다.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
