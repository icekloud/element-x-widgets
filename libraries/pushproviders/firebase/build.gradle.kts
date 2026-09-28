/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("UnstableApiUsage")

import extension.setupDependencyInjection
import extension.testCommonDependencies
import org.gradle.kotlin.dsl.withType
import org.sonarqube.gradle.SonarResolverTask

plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.libraries.pushproviders.firebase"

    buildFeatures {
        resValues = true
        // Element June: push gateway URL comes from CI (JUNE_PUSH_GATEWAY_URL), never from the repository
        buildConfig = true
    }

    defaultConfig {
        // Element June: own Sygnal push gateway, falls back to the upstream gateway when not configured
        val juneGateway = System.getenv("JUNE_PUSH_GATEWAY_URL")?.takeIf { it.isNotBlank() }
            ?: "https://matrix.org/_matrix/push/v1/notify"
        buildConfigField("String", "JUNE_PUSH_GATEWAY_URL", "\"$juneGateway\"")
    }

    buildTypes {
        getByName("release") {
            consumerProguardFiles("consumer-rules.keep")
        }
        register("nightly") {
            consumerProguardFiles("consumer-rules.keep")
            matchingFallbacks += listOf("release")
        }
    }
}

// Configure the SonarQube plugin to wait for the resource generation tasks to complete before running the analysis.
tasks.withType<SonarResolverTask>().configureEach {
    dependsOn("generateDebugResValues", "generateDebugAndroidTestResValues")
}

setupDependencyInjection()

dependencies {
    implementation(libs.androidx.corektx)
    implementation(projects.features.enterprise.api)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.core)
    implementation(projects.libraries.di)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.push.api)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.uiStrings)
    implementation(projects.libraries.troubleshoot.api)
    implementation(projects.services.toolbox.api)

    implementation(projects.libraries.pushstore.api)
    implementation(projects.libraries.pushproviders.api)

    api(platform(libs.google.firebase.bom))
    api("com.google.firebase:firebase-messaging") {
        exclude(group = "com.google.firebase", module = "firebase-core")
        exclude(group = "com.google.firebase", module = "firebase-analytics")
        exclude(group = "com.google.firebase", module = "firebase-measurement-connector")
    }

    testCommonDependencies(libs)
    testImplementation(libs.kotlinx.collections.immutable)
    testImplementation(projects.features.enterprise.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.push.test)
    testImplementation(projects.libraries.pushstore.test)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.libraries.troubleshoot.test)
    testImplementation(projects.services.toolbox.test)
}
