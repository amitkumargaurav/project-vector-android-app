import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use(::load)
    }
}

fun localProperty(name: String, fallback: String = ""): String =
    localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

fun envLocalProperty(name: String, env: String, fallback: String = ""): String =
    localProperty("${name}_${env.uppercase()}", localProperty(name, fallback))

fun quotedBuildConfig(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.projectvector.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.projectvector.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "VECTOR_WEB_URL", quotedBuildConfig(localProperty("VECTOR_WEB_URL")))
        buildConfigField("String", "VECTOR_TRUSTED_HOSTS", quotedBuildConfig(localProperty("VECTOR_TRUSTED_HOSTS")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedBuildConfig(localProperty("GOOGLE_WEB_CLIENT_ID")))
        buildConfigField("String", "VECTOR_AUTH_EXCHANGE_URL", quotedBuildConfig(localProperty("VECTOR_AUTH_EXCHANGE_URL")))
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "VECTOR_WEB_URL", quotedBuildConfig(envLocalProperty("VECTOR_WEB_URL", "dev")))
            buildConfigField("String", "VECTOR_TRUSTED_HOSTS", quotedBuildConfig(envLocalProperty("VECTOR_TRUSTED_HOSTS", "dev")))
            buildConfigField("String", "VECTOR_BACKEND_URL", quotedBuildConfig(envLocalProperty("VECTOR_BACKEND_URL", "dev")))
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedBuildConfig(envLocalProperty("GOOGLE_WEB_CLIENT_ID", "dev")))
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "VECTOR_WEB_URL", quotedBuildConfig(envLocalProperty("VECTOR_WEB_URL", "prod")))
            buildConfigField("String", "VECTOR_TRUSTED_HOSTS", quotedBuildConfig(envLocalProperty("VECTOR_TRUSTED_HOSTS", "prod")))
            buildConfigField("String", "VECTOR_BACKEND_URL", quotedBuildConfig(envLocalProperty("VECTOR_BACKEND_URL", "prod")))
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedBuildConfig(envLocalProperty("GOOGLE_WEB_CLIENT_ID", "prod")))
        }

    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kapt { correctErrorTypes = true }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("com.google.firebase:firebase-messaging-ktx:24.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.jakewharton.timber:timber:5.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
