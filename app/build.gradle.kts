import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization") version "2.3.20"
    id("com.google.devtools.ksp")
}

android {
    namespace = "de.timklge.karooroutegraph"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.timklge.karooroutegraph"
        minSdk = 26
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 100 + (System.getenv("BUILD_NUMBER")?.toInt() ?: 1)
        versionName = System.getenv("RELEASE_VERSION") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val env: MutableMap<String, String> = System.getenv()
            keyAlias = env["KEY_ALIAS"]
            keyPassword = env["KEY_PASSWORD"]

            val base64keystore: String = env["KEYSTORE_BASE64"] ?: ""
            val keystoreFile: File = File.createTempFile("keystore", ".jks")
            keystoreFile.writeBytes(Base64.getDecoder().decode(base64keystore))
            storeFile = keystoreFile
            storePassword = env["KEYSTORE_PASSWORD"]
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("generateManifest") {
    description = "Generates manifest.json with current version information"
    group = "build"

    doLast {
        val manifestFile = file("$projectDir/manifest.json")
        val manifest = mapOf(
            "label" to "RouteGraph",
            "packageName" to "de.timklge.karooroutegraph",
            "iconUrl" to "https://github.com/timklge/karoo-routegraph/releases/latest/download/karoo-routegraph.png",
            "latestApkUrl" to "https://github.com/timklge/karoo-routegraph/releases/latest/download/app-release.apk",
            "latestVersion" to android.defaultConfig.versionName,
            "latestVersionCode" to android.defaultConfig.versionCode,
            "developer" to "github.com/timklge",
            "description" to "Open-source extension that provides an elevation graph datafield depicting the complete current route, including climbs and POIs (e. g. checkpoints and refueling stops). Also provides a minimap datafield and a POI navigation datafield to look up upcoming POIs of certain categories on the route (e. g. supermarkets).",
            "releaseNotes" to "* Show open / closed status next to POIs, include final off-route segment in ETA calculation\n* Add ETA to nearby POI lookup result list\n* Fix nearby POI list always shows distance to first occurrence of POI on route\n* Include OSM railway=halt nodes in station POI category",
            "screenshotUrls" to listOf(
                "https://github.com/timklge/karoo-routegraph/releases/latest/download/horizontal_routegraph.png",
                "https://github.com/timklge/karoo-routegraph/releases/latest/download/minimap.png",
                "https://github.com/timklge/karoo-routegraph/releases/latest/download/poinav.png",
                "https://github.com/timklge/karoo-routegraph/releases/latest/download/vertical_routegraph.png",
                "https://github.com/timklge/karoo-routegraph/releases/latest/download/routegraph_surface_conditions.png",
                "https://github.com/timklge/karoo-routegraph/releases/latest/download/chevrons.png",
            ),
            "tags" to listOf(
                "performance"
            )
        )

        val gson = groovy.json.JsonBuilder(manifest).toPrettyString()
        manifestFile.writeText(gson)
        println("Generated manifest.json with version ${android.defaultConfig.versionName} (${android.defaultConfig.versionCode})")
    }
}

tasks.named("assemble") {
    dependsOn("generateManifest")
}



dependencies {
    implementation(libs.mapbox.sdk.turf)
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.appwidget.preview)
    implementation(libs.androidx.glance.preview)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.mapsforge.map.reader)
    implementation(libs.osm4j.core)
    implementation(libs.osm4j.pbf)
    implementation(libs.okhttp)
    implementation(libs.antlr4.runtime)
    testImplementation(libs.testng)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}