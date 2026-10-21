import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val firebaseJsonFromEnv: String? = System.getenv("GOOGLE_SERVICES_JSON")
    ?: System.getenv("GOOGLE_SERVICES_JSON_B64")?.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it).decodeToString() }

val firebaseFile = file("google-services.json")
if (!firebaseFile.exists()) {
    val firebaseJson = firebaseJsonFromEnv
    if (firebaseJson.isNullOrBlank()) {
        throw GradleException("Faltando app/google-services.json ou Google secret GOOGLE_SERVICES_JSON/GOOGLE_SERVICES_JSON_B64. O build foi interrompido para não gerar APK sem a nuvem original.")
    }
    firebaseFile.writeText(firebaseJson)
}
apply(plugin = "com.google.gms.google-services")

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: "").replace("\"", "")

android {
    namespace = "space.neural.luna"
    compileSdk = 35

    defaultConfig {
        applicationId = "space.neural.luna"
        minSdk = 26
        targetSdk = 35
        versionCode = 112
        versionName = "0.1.12"

        buildConfigField("String", "LIVEKIT_URL", "\"${cfg("LIVEKIT_URL")}"")
        buildConfigField("String", "LIVEKIT_TOKEN_ENDPOINT", "\"${cfg("LIVEKIT_TOKEN_ENDPOINT")}"")
        val chat = cfg("LUNA_CHAT_ENDPOINT").ifBlank {
            cfg("LIVEKIT_TOKEN_ENDPOINT").takeIf { it.isNotBlank() }?.trimEnd('/')?.removeSuffix("/token")?.let { "$it/chat" } ?: ""
        }
        buildConfigField("String", "LUNA_CHAT_ENDPOINT", "\"$chat\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", true.toString())
        buildConfigField("String", "CHECKOUT_URL", "\"${cfg("CHECKOUT_URL").ifBlank { "https://checkout.infinitepay.io/mduseimports/nDdXiu2DrZ" }}\"")
        buildConfigField("String", "SUPPORT_WHATSAPP", "\"${cfg("SUPPORT_WHATSAPP").ifBlank { "5533987044866" }}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { t ->
            System.getProperty("luna.shots")?.let { t.systemProperty("luna.shots", it) }
            System.getProperty("luna.emulador")?.let { t.systemProperty("luna.emulador", it) }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.zxing.core)
    implementation(libs.livekit.android)
    implementation(libs.osmdroid.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
}
