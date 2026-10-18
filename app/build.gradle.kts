import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// google-services.json é local (está no .gitignore). Sem ele, o app compila
// normalmente e a sincronização com o Firebase fica desligada.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("app/google-services.json não encontrado: build sem Firebase (sincronização desligada).")
}

// Configuração do LiveKit lida de local.properties ou variáveis de ambiente.
// Nunca coloque segredos (API secret do LiveKit) aqui nem no repositório.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: "").replace("\"", "")

android {
    namespace = "space.neural.luna"
    compileSdk = 37

    defaultConfig {
        applicationId = "space.neural.luna"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "LIVEKIT_URL", "\"${cfg("LIVEKIT_URL")}\"")
        buildConfigField("String", "LIVEKIT_TOKEN_ENDPOINT", "\"${cfg("LIVEKIT_TOKEN_ENDPOINT")}\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasGoogleServices.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // Armazenamento local (fonte da verdade, funciona offline)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Sincronização em segundo plano quando houver internet
    implementation(libs.work.runtime.ktx)

    // Firebase (plano Spark): login anônimo + Firestore
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Sala de voz / rádio
    implementation(libs.livekit.android)
}
