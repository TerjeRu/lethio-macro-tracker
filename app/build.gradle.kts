import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val offRelayUrl = providers.gradleProperty("offRelayUrl").orElse("").get()
if (offRelayUrl.isNotEmpty()) {
    val relay = runCatching { URI(offRelayUrl) }.getOrNull()
    require(
        relay != null && relay.scheme == "https" && !relay.host.isNullOrBlank() &&
            relay.rawUserInfo == null && relay.rawQuery == null && relay.rawFragment == null &&
            offRelayUrl.all { it.code in 33..126 && it != '"' && it != '\\' }
    ) { "offRelayUrl must be an HTTPS URL without credentials, a query or a fragment" }
}

android {
    namespace = "com.lethio.macros"
    compileSdk = 36

    bundle {
        language { enableSplit = false }
    }

    defaultConfig {
        applicationId = "com.lethio.macros"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.0"
        buildConfigField("String", "OFF_RELAY_URL", "\"$offRelayUrl\"")
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    lint {
        abortOnError = true
        error += setOf(
            "ContentDescription", "ClickableViewAccessibility", "LabelFor",
            "KeyboardInaccessibleWidget",
        )
    }
}

val checkFoodAssets = tasks.register("checkFoodAssets") {
    doLast {
        for (asset in listOf("seed.db", "compound-lexicon.txt")) {
            check(layout.projectDirectory.file("src/main/assets/$asset").asFile.isFile) {
                "Missing food asset: $asset. Generate the food assets with tools/build_nutrition_db.py; " +
                    "run python tools/build_nutrition_db.py --help for source and output options."
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(checkFoodAssets) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
