plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Ключ подписи кладёт CI: путь и пароль приходят переменными. */
val keystorePath: String? = System.getenv("MOON_KEYSTORE")
val keystorePass: String? = System.getenv("MOON_KEYSTORE_PASS")

android {
    namespace = "dev.moonmc.moonmc"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.moonmc.moonmc"
        minSdk = 29
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    sourceSets["main"].apply {
        // Java-исходники, res и assets готовит CI из дерева движка
        java.srcDirs("src/main/java")
        jniLibs.srcDirs("src/main/jniLibs")
        assets.srcDirs("src/main/assets")
        res.srcDirs("src/main/res")
    }

    signingConfigs {
        if (keystorePath != null && keystorePass != null) {
            create("ci") {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = "horizon"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("ci")
        }
        debug {
            signingConfig = signingConfigs.findByName("ci")
        }
    }

    androidResources {
        // JRE и экспортёры лежат в assets как есть: те же архивы, что и в старой
        // aapt2-сборке (там они тоже были несжатыми). Сжатие ломало распаковку.
        noCompress += listOf("xz", "tar.xz", "zip", "json")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Игра собирается целиком из подготовленного дерева (java/res/assets от движка),
    // поэтому релизный lint не должен валить сборку.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*")
    }
}

dependencies {
    implementation(project(":layercontroller"))
    implementation(project(":inputmap"))
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
