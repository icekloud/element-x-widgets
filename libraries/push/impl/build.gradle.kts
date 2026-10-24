import config.BuildTimeConfig
import extension.buildConfigFieldStr
import extension.setupDependencyInjection
import extension.testCommonDependencies
import java.net.URI
import java.security.MessageDigest

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-compose-library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "io.element.android.libraries.push.impl"

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        val defaultPusherAppId = "im.vector.app.android"
        getByName("release") {
            buildConfigFieldStr(
                name = "PUSHER_APP_ID",
                value = BuildTimeConfig.PUSHER_APP_ID_RELEASE ?: defaultPusherAppId,
            )
        }
        getByName("debug") {
            buildConfigFieldStr(
                name = "PUSHER_APP_ID",
                value = BuildTimeConfig.PUSHER_APP_ID_DEBUG ?: defaultPusherAppId,
            )
        }
        register("nightly") {
            matchingFallbacks += listOf("release")
            buildConfigFieldStr(
                name = "PUSHER_APP_ID",
                value = BuildTimeConfig.PUSHER_APP_ID_NIGHTLY ?: defaultPusherAppId,
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Element June: the model of the built-in voice is read straight from the package, so it must not be compressed
    androidResources {
        noCompress += listOf("onnx")
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(juneVoiceAssets)
        }
    }
}

/*
 * Element June: the files of the Korean voice built into the app (Supertonic 3, voice F1).
 *
 * They are about 380 MB, so they are not kept in the repository: the build downloads them once from the frozen revision of the model
 * and checks the hash of every one of them. A file that does not match stops the build. They land in the assets of the app.
 */
val juneVoiceRevision = "aafc6e32416a594460b32413efc49d7fe4ce6d46"

val juneVoiceFiles = mapOf(
    "onnx/duration_predictor.onnx" to "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
    "onnx/text_encoder.onnx" to "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
    "onnx/vector_estimator.onnx" to "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c",
    "onnx/vocoder.onnx" to "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba",
    "onnx/unicode_indexer.json" to "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f",
    "onnx/tts.json" to "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09",
    "voice_styles/F1.json" to "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2",
    "config.json" to "4099082b107a9d4029849ac76b89eca65e03732660969c2babe5bf308c7357f2",
    // The licence of the model (BigScience OpenRAIL-M) travels with it, so it is in the app next to the files it covers
    "LICENSE" to "0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f",
)

val juneVoiceAssets: Provider<Directory> = layout.buildDirectory.dir("june-voice-assets")

val juneVoiceDownload = tasks.register("juneVoiceDownload") {
    description = "Downloads the files of the Korean voice built into the app and checks their hashes."
    val target = juneVoiceAssets
    val revision = juneVoiceRevision
    val files = juneVoiceFiles
    outputs.dir(target)
    outputs.upToDateWhen { task ->
        val folder = target.get().asFile.resolve("june_voice")
        files.all { (path, hash) -> folder.resolve(path.substringAfterLast('/')).takeIf { it.isFile }?.let { sha256(it) == hash } == true }
            .also { if (!it) task.logger.lifecycle("The files of the built-in voice have to be downloaded") }
    }
    doLast {
        val folder = target.get().asFile.resolve("june_voice").apply { mkdirs() }
        files.forEach { (path, expected) ->
            val file = folder.resolve(path.substringAfterLast('/'))
            if (file.isFile && sha256(file) == expected) return@forEach
            val source = "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$revision/$path"
            logger.lifecycle("Downloading the built-in voice file $path")
            URI(source).toURL().openStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            val actual = sha256(file)
            if (actual != expected) {
                file.delete()
                error("The built-in voice file $path does not match what is expected ($actual instead of $expected)")
            }
        }
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(juneVoiceDownload)
}

setupDependencyInjection()

dependencies {
    implementation(libs.androidx.corektx)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.network.retrofit.bom))
    implementation(libs.network.retrofit)
    implementation(libs.serialization.json)
    implementation(libs.coil)

    // Element June: the Korean voice built into the app (Supertonic 3, voice F1) runs with it
    implementation(libs.onnxruntime.android)

    implementation(libs.sqldelight.driver.android)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite)
    implementation(libs.sqldelight.coroutines)
    implementation(projects.libraries.encryptedDb)

    implementation(projects.appconfig)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.core)
    implementation(projects.libraries.dateformatter.api)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.di)
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.network)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.matrixui)
    implementation(projects.libraries.matrixmedia.api)
    implementation(projects.features.networkmonitor.api)
    implementation(projects.libraries.preferences.api)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.uiStrings)
    implementation(projects.libraries.troubleshoot.api)
    implementation(projects.libraries.workmanager.api)
    implementation(projects.features.announcement.api)
    implementation(projects.features.call.api)
    implementation(projects.features.widgets.api)
    implementation(projects.features.enterprise.api)
    implementation(projects.features.lockscreen.api)
    implementation(projects.libraries.featureflag.api)
    api(projects.libraries.pushproviders.api)
    api(projects.libraries.pushstore.api)
    api(projects.libraries.push.api)

    implementation(projects.services.analytics.api)
    implementation(projects.services.appnavstate.api)
    implementation(projects.services.toolbox.api)

    testCommonDependencies(libs)
    testImplementation(libs.coil.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.matrixmedia.test)
    testImplementation(projects.libraries.preferences.test)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.libraries.push.test)
    testImplementation(projects.libraries.pushproviders.test)
    testImplementation(projects.libraries.pushstore.test)
    testImplementation(projects.libraries.troubleshoot.test)
    testImplementation(projects.libraries.workmanager.test)
    testImplementation(projects.features.announcement.test)
    testImplementation(projects.features.call.test)
    testImplementation(projects.features.enterprise.test)
    testImplementation(projects.features.lockscreen.test)
    testImplementation(projects.features.networkmonitor.test)
    testImplementation(projects.services.appnavstate.impl)
    testImplementation(projects.services.appnavstate.test)
    testImplementation(projects.services.analytics.test)
    testImplementation(projects.services.toolbox.impl)
    testImplementation(projects.services.toolbox.test)
    testImplementation(projects.libraries.featureflag.test)
    testImplementation(libs.kotlinx.collections.immutable)
}

sqldelight {
    databases {
        create("PushDatabase") {
            schemaOutputDirectory = File("src/main/sqldelight/databases")
            verifyMigrations = true
        }
    }
}
