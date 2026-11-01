plugins {
    id("com.android.library")
}

android {
    namespace = "com.movtery.inputmap"
    compileSdk = 37

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // ключи помечены @Nullable из androidx.annotation
    implementation("androidx.annotation:annotation:1.9.1")
}
