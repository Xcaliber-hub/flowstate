plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Keep the same keystore for every release. Credentials stay outside the source archive.
fun signingValue(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }
val releaseStoreFile = signingValue("FLOWSTATE_KEYSTORE_FILE")
val releaseStorePassword = signingValue("FLOWSTATE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("FLOWSTATE_KEY_ALIAS")
val releaseKeyPassword = signingValue("FLOWSTATE_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
require(releaseSigningValues.all { it == null } || releaseSigningValues.all { it != null }) {
    "Supply all four FLOWSTATE_KEYSTORE_FILE, FLOWSTATE_KEYSTORE_PASSWORD, FLOWSTATE_KEY_ALIAS and FLOWSTATE_KEY_PASSWORD values, or none."
}

android {
    namespace = "com.flowstate.focus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flowstate.focus"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.3"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("persistentRelease") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("persistentRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Optional cloud groups: initialized only when flowstate_firebase_* resources are supplied.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
