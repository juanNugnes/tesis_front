import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    jacoco
}

val apiBaseUrl = providers.gradleProperty("apiBaseUrl").orNull ?: "http://10.0.2.2:8000/"
require(apiBaseUrl.endsWith("/")) { "apiBaseUrl must end with /" }
val apiBaseUrlBuildConfigValue = "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.juan.habitos"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.juan.habitos"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", apiBaseUrlBuildConfigValue)
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "DEMO_MODE", providers.gradleProperty("demoMode").orNull?.toBooleanStrictOrNull()?.toString() ?: "true")
            buildConfigField("boolean", "ADULT_MESSAGES_DEMO_MODE", providers.gradleProperty("adultMessagesDemoMode").orNull?.toBooleanStrictOrNull()?.toString() ?: "false")
            buildConfigField("boolean", "AUTOMATIC_ACTIVITIES_DEMO_MODE", providers.gradleProperty("automaticActivitiesDemoMode").orNull?.toBooleanStrictOrNull()?.toString() ?: "false")
        }
        release {
            buildConfigField("boolean", "DEMO_MODE", "false")
            buildConfigField("boolean", "ADULT_MESSAGES_DEMO_MODE", "false")
            buildConfigField("boolean", "AUTOMATIC_ACTIVITIES_DEMO_MODE", "false")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoDebugReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*"
    )

    val kotlinClasses = fileTree(
        layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    ) {
        exclude(fileFilter)
    }

    val javaClasses = fileTree(
        layout.buildDirectory.dir("intermediates/javac/debug/classes")
    ) {
        exclude(fileFilter)
    }

    classDirectories.setFrom(files(kotlinClasses, javaClasses))

    sourceDirectories.setFrom(
        files(
            "$projectDir/src/main/java",
            "$projectDir/src/main/kotlin"
        )
    )

    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
            )
        }
    )
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
