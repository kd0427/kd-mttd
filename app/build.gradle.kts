import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ksp)
}

// 릴리스 서명 정보. 저장소에 커밋하지 않는다 (.gitignore).
// keystore.properties 가 없으면 release 빌드는 서명되지 않은 채로 나온다 (CI/기여자용).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { s -> load(s) }
}

android {
    // Kotlin 소스의 기존 namespace는 유지한다. applicationId만 분리해 원본 앱과
    // 공존 설치할 수 있게 한다.
    namespace = "com.mttd"
    compileSdk = 34
    // SPAKE2 페어링 JNI 빌드용. NDK 26(AGP 기본값)의 bionic jni.h 는 <stdint.h> 를 스스로
    // 안 끌어와서 프리팹 헤더와 조합 시 uint8_t 등이 깨진다 — Shizuku 본가가 실제 검증해서
    // 쓰는 버전으로 고정해 같은 조합을 재현한다.
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.doyoon.kdmttd"
        // 무선 디버깅이 Android 11+ 기능이라 그 아래로는 붙을 방법이 없다.
        minSdk = 30
        targetSdk = 34
        // 릴리스마다 반드시 올릴 것. 안 올리면 시스템이 업데이트로 인식하지 않는다.
        // versionName 은 GitHub 릴리스 태그(vX.Y.Z)와 맞춘다 — 인앱 업데이트 확인이 이걸로 비교.
        versionCode = 67
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 개인 GitHub 저장소를 만든 뒤 `-PupdateRepo=owner/repository` 로 지정한다.
        // 빈 값은 업데이트 확인을 비활성화한다. 원본 저장소 릴리스를 안내하지 않는다.
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: ""}\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // SPAKE2 페어링 핸드셰이크(1회성)와 shell UID 데몬을 띄우는 starter 바이너리.
    // RikkaApps/Shizuku 포팅 — THIRD_PARTY_NOTICES.md 참고.
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
        // CMake 가 boringssl AAR(prefab 패키지)을 find_package() 로 찾으려면 필요.
        prefab = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // libmttd_starter.so 는 dlopen 대상이 아니라 진짜 실행 파일이라(DirectDaemonStarter
            // 클래스 doc 참조) APK zip 안의 압축 엔트리가 아니라 디스크에 실제 파일로 풀려 있어야
            // shell 이 실행할 수 있다. AGP 기본값(false)은 압축된 채로 두므로 실행이 불가능하다.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // bcpkix/bcutil/bcprov 전이 의존성 버전이 서로 살짝 달라(1.80 vs 1.80.2) 같은 OSGi
            // 매니페스트 경로가 중복으로 잡힌다. 코드 동작과 무관한 리소스.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }
}

// 산출물 파일명 — 기본값(app-release.apk)은 어떤 앱인지, 무슨 버전인지 알 수 없다.
// -> kd-mttd-0.3.11-kd-release.apk / kd-mttd-0.3.11-kd-debug.apk
// (파일명은 설치·업데이트 판단과 무관 — 안드로이드는 applicationId + 서명만 본다.)
base {
    archivesName.set("kd-mttd-${android.defaultConfig.versionName}")
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    // Room — 회차 기록을 디스크로 내려 프로세스 메모리 증가를 막는다
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Compose (BOM 이 나머지 버전 확정)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Coroutines & Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 무선 adb 페어링/연결 (RikkaApps/Shizuku 포팅, THIRD_PARTY_NOTICES.md 참고).
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    // TLS exporter(RFC 5705) 공개 API 용 — AdbKey.sslContext 주석 참고.
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // Networking & protobuf
    implementation(libs.okhttp)
    implementation(libs.protobuf.kotlin.lite)

    // Image loading
    implementation(libs.coil.compose)

    // 단위 테스트 — SessionAggregator 는 순수 Kotlin(Android 의존성 없음)이라 JVM 테스트로 충분.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
