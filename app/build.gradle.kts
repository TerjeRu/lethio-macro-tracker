import java.util.Properties
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing reads the gitignored keystore.properties. Without it the release build is unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

// Contributions are disabled unless a relay URL is supplied. No credential is ever in the build.
val requestedOffRelayPropertiesPath = providers.gradleProperty("offRelayPropertiesPath").orNull
val offRelayPropertiesFile = requestedOffRelayPropertiesPath
    ?.let(rootProject::file)
    ?: rootProject.file("off-relay.properties")
if (requestedOffRelayPropertiesPath != null && !offRelayPropertiesFile.isFile) {
    throw GradleException("The explicitly configured OFF relay properties file does not exist")
}
val offRelayProperties = Properties().apply {
    if (offRelayPropertiesFile.exists()) {
        offRelayPropertiesFile.inputStream().use { load(it) }
    }
}
val offRelayUrlDebug: String = offRelayProperties.getProperty("url").orEmpty()
val productionRelayUrl = "https://www.lethio.com/v1/off/contributions"
if (offRelayUrlDebug == productionRelayUrl &&
    offRelayProperties.getProperty("allowProductionRelay") != "true"
) {
    throw GradleException(
        "A debug build may use the production OFF relay only when its ignored properties file " +
            "contains allowProductionRelay=true",
    )
}

android {
    namespace = "com.lethio.macros"
    compileSdk = 36

    // Keep every translation in the base APK: the in-app language picker cannot fetch splits.
    bundle {
        language {
            enableSplit = false
        }
    }

    defaultConfig {
        applicationId = "com.lethio.macros"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // A public URL only; authentication lives in the relay.
        buildConfigField(
            "String",
            "OFF_RELAY_URL",
            "\"https://www.lethio.com/v1/off/contributions\"",
        )

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            buildConfigField("String", "OFF_RELAY_URL", "\"$offRelayUrlDebug\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false

        // View/XML accessibility checks. The app is pure Compose, so these guard a future XML
        // layout; the real accessibility gate is the Compose tests in `androidTest`.
        error += setOf(
            "ContentDescription",
            "ClickableViewAccessibility",
            "LabelFor",
            "KeyboardInaccessibleWidget",
        )

        // `DefaultLocale` stays a warning: displayed numbers follow the device locale on purpose
        // ("1,5"), and NumericInput parses both separators.
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG gates StrictMode
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // MigrationTestHelper reads the exported schemas at runtime.
    sourceSets {
        // Optional debug-only asset overlay for verifying a database under development.
        providers.gradleProperty("sourceVerificationAssets").orNull?.let { path ->
            require(path.isNotBlank()) { "Pass the complete -PsourceVerificationAssets=... argument as one quoted argument" }
            val scratch = file(path).canonicalFile
            val buildRoot = rootProject.layout.buildDirectory.get().asFile.canonicalFile
            require(scratch.toPath().startsWith(buildRoot.toPath())) { "Source verification assets must be under build/" }
            require(scratch.resolve("seed.db").isFile) { "Scratch source verification seed is missing" }
            getByName("debug").assets.srcDir(scratch)
        }
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
            // Use the same frozen fixtures for host exports and real repository measurements.
            assets.srcDir("$projectDir/src/test/resources")
            // Small synthetic databases produced by the real writer, for storage tests.
            assets.srcDir("${rootProject.layout.buildDirectory.get().asFile}/nutrition-test-assets")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val pythonExecutable = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
// Synthetic databases for the instrumented storage tests. Skipped where the test sources are
// absent, as in the public source tree, so lint and the build still run.
val nutritionContract = rootProject.file("app/src/test/resources/nutrition-contract.json")
val generateNutritionTestAssets = tasks.register<Exec>("generateNutritionTestAssets") {
    onlyIf { nutritionContract.exists() }
    workingDir(rootProject.projectDir)
    val output = rootProject.layout.buildDirectory.dir("nutrition-test-assets")
    inputs.files(rootProject.file("tools/create_nutrition_test_assets.py"),
        rootProject.file("tools/build_nutrition_db.py"), rootProject.file("tools/nutrition_values.py"),
        nutritionContract,
        rootProject.file("app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json"))
    outputs.dir(output)
    commandLine(providers.gradleProperty("sourceTestPython").orElse(pythonExecutable).get(),
        "tools/create_nutrition_test_assets.py", "--out", output.get().asFile.absolutePath)
}
tasks.matching {
    it.name == "mergeDebugAndroidTestAssets" || it.name == "generateDebugAndroidTestLintModel" ||
        it.name == "lintAnalyzeDebugAndroidTest"
}.configureEach {
    dependsOn(generateNutritionTestAssets)
}

// The food database is built, not committed. Without it the app installs and finds nothing, so
// the build fails early with the command that produces it.
val checkSeedDatabase = tasks.register("checkSeedDatabase") {
    val seed = layout.projectDirectory.file("src/main/assets/seed.db").asFile
    outputs.upToDateWhen { seed.exists() }
    doLast {
        if (!seed.exists()) {
            throw GradleException(
                """
                The bundled food database is missing:
                    ${seed.path}

                It is generated, not committed. README.md, under "Build the food database", has the
                command for a real build from the publishers' data. --build-version must be higher
                than the last shipped build, or installed apps will keep their old database.

                To only compile, test or lint, build an empty but schema-correct database, as CI does:

                    python tools/build_nutrition_db.py --stub \
                        --schema app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json \
                        --out    app/src/main/assets/seed.db \
                        --build-version 1

                It has zero rows, so every search returns nothing and the app is not worth
                running against it. It exists so that a clean clone can compile and test.
                """.trimIndent()
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(checkSeedDatabase) }

val softwareNoticesFile = layout.projectDirectory.file("src/main/assets/software-licenses.json").asFile

tasks.register("exportLicenseInventory") {
    group = "verification"
    doLast {
        val artifacts = configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.map {
                mapOf(
                    "group" to it.moduleVersion.id.group,
                    "name" to it.moduleVersion.id.name,
                    "version" to it.moduleVersion.id.version,
                    "file" to it.file.absolutePath,
                )
            }
        val output = layout.buildDirectory.file("license-inventory.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.toJson(artifacts))
    }
}

val checkSoftwareNotices = tasks.register("checkSoftwareNotices") {
    doLast {
        check(softwareNoticesFile.isFile) { "Missing software-licenses.json; regenerate software notices" }
        val data = groovy.json.JsonSlurper().parse(softwareNoticesFile) as Map<*, *>
        val recorded = (data["artifacts"] as List<*>).map { it.toString() }.toSet()
        val resolved = configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() }.toSet()
        check(recorded == resolved) { "Runtime dependencies changed; regenerate software notices" }
    }
}
tasks.named("preBuild") { dependsOn(checkSoftwareNotices) }

val releaseSecretVerifier = rootProject.file("tools/verify_release_secrets.py")
val generatedBuildConfig = layout.buildDirectory.dir("generated/source/buildConfig")
val requestedLegacyPropertiesPath = providers.gradleProperty("legacyOffProperties").orNull
val legacyPropertiesFile = requestedLegacyPropertiesPath
    ?.let(rootProject::file)
    ?: rootProject.file("off-account.properties").takeIf { it.isFile }
fun verificationCommand(artifact: File): List<String> = buildList {
    add(pythonExecutable)
    add(releaseSecretVerifier.absolutePath)
    add(artifact.absolutePath)
    add("--generated-dir")
    add(generatedBuildConfig.get().asFile.absolutePath)
    legacyPropertiesFile?.let {
        add("--legacy-properties")
        add(it.absolutePath)
    }
}

val verifyReleaseBundleSecrets = tasks.register<Exec>("verifyReleaseBundleSecrets") {
    group = "verification"
    doFirst {
        commandLine(verificationCommand(layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile))
    }
}

val verifyReleaseApkSecrets = tasks.register<Exec>("verifyReleaseApkSecrets") {
    group = "verification"
    doFirst {
        val artifacts = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            .listFiles { file -> file.isFile && file.extension == "apk" }
            ?.toList()
            .orEmpty()
        if (artifacts.size != 1) {
            throw GradleException("Expected exactly one release APK for credential inspection")
        }
        commandLine(verificationCommand(artifacts.single()))
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy(verifyReleaseBundleSecrets)
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseApkSecrets)
}

// DataSourceTest and SearchLadderMirrorTest read the Python under tools/ to hold Kotlin/Python
// invariants. Gradle cannot infer that, so declare it, or the tests stay UP-TO-DATE when only the
// Python changed.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.fileTree("tools") { include("*.py") })
        .withPropertyName("pythonPipelineMirrors")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlite.bundled)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Barcode decoding on the device
    implementation(libs.zxing.cpp)

    // Preferences
    implementation(libs.androidx.datastore.preferences)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // HTTP (opt-in Open Food Facts lookup and contribution)
    implementation(libs.okhttp)

    // JVM unit tests
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
