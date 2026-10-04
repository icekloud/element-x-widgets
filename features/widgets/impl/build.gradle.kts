import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2026 Element June contributors.
 * Based on Element X Android, Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.features.widgets.impl"
}

setupDependencyInjection()

dependencies {
    api(projects.features.widgets.api)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.di)
    implementation(projects.libraries.core)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.sessionStorage.api)
    // Element June: the voice shortcut records with the very same recorder as the message composer,
    // and shares its minimum recording length with the composer events.
    implementation(projects.libraries.voicerecorder.api)
    implementation(projects.libraries.voicerecorder.impl)
    implementation(projects.libraries.textcomposer.impl)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.corektx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coroutines.core)
    implementation(libs.timber)

    testCommonDependencies(libs)
}
