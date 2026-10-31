import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

val signingPropertiesFile = layout.projectDirectory.file("signing/signing.local.properties").asFile
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.minavigatering.probe"
    compileSdk = 37

    defaultConfig {
        // 与 namespace 故意分开。
        //
        // Mi Fitness（com.xiaomi.wearable）在**首次授权**时按 Android applicationId 记下
        // 调用方的证书指纹，之后每次 checkPermission / sendMessage 都比对，而且
        // 「设备授权管理」里的开关只控制权限、不会重写这条记录。所以换过一次证书的包名
        // 就永久失配（dbFingerprint ≠ curFingerPrint → SignatureVerifyFailedException），
        // 卸载重装也清不掉（记录跟着包名活着）。
        //
        // 换一个 applicationId 等于在 Mi Fitness 里换一张白纸：没有旧记录，首次授权时
        // 才会登记当前证书。签名材料已固化进 GitHub Secrets，之后证书不再变，只换这一次。
        // namespace 保持原值，组件类名（.navigation.MapsNotificationService）因此不变。
        applicationId = "com.mapring.probe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingProperties.isNotEmpty()) {
            create("probeDebug") {
                storeFile = layout.projectDirectory.file("signing/debug.jks").asFile
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (signingProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("probeDebug")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(files("libs/xms-wearable-lib_1.4_release.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}

// Generate the local debug keystore when it is missing; it is intentionally untracked.
val generateDebugSigning = tasks.register<Exec>("generateDebugSigning") {
    val script = rootProject.layout.projectDirectory.file("scripts/gen-debug-signing.py").asFile
    val keystore = layout.projectDirectory.file("signing/debug.jks").asFile
    onlyIf { !keystore.exists() }
    workingDir = rootProject.projectDir
    commandLine("python", script.absolutePath)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateDebugSigning)
}
