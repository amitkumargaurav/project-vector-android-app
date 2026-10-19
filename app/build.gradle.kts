import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

val hasGoogleServicesConfig = sequenceOf(
    file("google-services.json"),
    file("src/dev/google-services.json"),
    file("src/prod/google-services.json"),
    file("src/debug/google-services.json"),
    file("src/release/google-services.json"),
    file("src/main/google-services.json"),
).any { it.exists() }

if (hasGoogleServicesConfig) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
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
fun unquotedLocalProperty(value: String): String = value
    .removeSurrounding("\"")
    .removeSurrounding("'")

android {
    namespace = "com.projectvector.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.projectvector.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "VECTOR_WEB_URL", quotedBuildConfig(localProperty("VECTOR_WEB_URL")))
        buildConfigField("String", "VECTOR_TRUSTED_HOSTS", quotedBuildConfig(localProperty("VECTOR_TRUSTED_HOSTS")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedBuildConfig(localProperty("GOOGLE_WEB_CLIENT_ID")))
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            versionNameSuffix = "-dev"
            buildConfigField("String", "VECTOR_WEB_URL", quotedBuildConfig(envLocalProperty("VECTOR_WEB_URL", "dev")))
            buildConfigField("String", "VECTOR_TRUSTED_HOSTS", quotedBuildConfig(envLocalProperty("VECTOR_TRUSTED_HOSTS", "dev")))
            buildConfigField("String", "VECTOR_BACKEND_URL", quotedBuildConfig(envLocalProperty("VECTOR_BACKEND_URL", "dev")))
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedBuildConfig(envLocalProperty("GOOGLE_WEB_CLIENT_ID", "dev")))
            manifestPlaceholders["appName"] = unquotedLocalProperty(envLocalProperty("APP_NAME", "dev", "Vector Dev"))
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "VECTOR_WEB_URL", quotedBuildConfig(envLocalProperty("VECTOR_WEB_URL", "prod")))
            buildConfigField("String", "VECTOR_TRUSTED_HOSTS", quotedBuildConfig(envLocalProperty("VECTOR_TRUSTED_HOSTS", "prod")))
            buildConfigField("String", "VECTOR_BACKEND_URL", quotedBuildConfig(envLocalProperty("VECTOR_BACKEND_URL", "prod")))
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedBuildConfig(envLocalProperty("GOOGLE_WEB_CLIENT_ID", "prod")))
            manifestPlaceholders["appName"] = unquotedLocalProperty(envLocalProperty("APP_NAME", "prod", "Vector"))
        }

    }

    signingConfigs {
        create("appSigning") {
            storeFile = file(localProperty("storeFile"))
            keyAlias = localProperty("keyAlias")
            storePassword = localProperty("storePassword")
            keyPassword = localProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("appSigning")
            isMinifyEnabled = true
            isShrinkResources = true
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
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get() }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kapt { correctErrorTypes = true }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)
    implementation(libs.google.play.services.auth)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.timber)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
