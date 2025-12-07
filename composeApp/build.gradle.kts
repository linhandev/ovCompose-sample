/*
 * Tencent is pleased to support the open source community by making ovCompose available.
 * Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.databinding.tool.ext.capitalizeUS
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.cocoapods)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    cocoapods {
        homepage = "something must not be null"
        summary = "something must not be null"
        version = "1.0"
        ios.deploymentTarget = "13.0"
        framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    ohosArm64 {
        binaries.sharedLib {
            baseName = "kn"
            export(libs.compose.multiplatform.export)
        }

        val main by compilations.getting

        val resource by main.cinterops.creating {
            defFile(file("src/ohosArm64Main/cinterop/resource.def"))
            includeDirs(file("src/ohosArm64Main/cinterop/include"))
        }
    }

    sourceSets {

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.atomicFu)
        }

        val ohosArm64Main by getting {
            dependencies {
                api(libs.compose.multiplatform.export)
            }
        }
    }
}

android {
    namespace = "com.tencent.compose"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.tencent.compose"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.ui.tooling)
}

arrayOf("debug", "release").forEach { type ->
    tasks.register<Copy>("publish${type.capitalizeUS()}BinariesToHarmonyApp") {
        group = "harmony"
        dependsOn("link${type.capitalizeUS()}SharedOhosArm64")
        into(rootProject.file("harmonyApp"))
        from("build/bin/ohosArm64/${type}Shared/libkn_api.h") {
            into("entry/src/main/cpp/include/")
        }
        from(project.file("build/bin/ohosArm64/${type}Shared/libkn.so")) {
            into("/entry/libs/arm64-v8a/")
        }
    }
}

arrayOf("debug", "release").forEach { type ->

    tasks.register<Copy>("startHarmonyApp${type.capitalizeUS()}") {
        group = "harmony"
        dependsOn("link${type.capitalizeUS()}SharedOhosArm64")
        
        // Disable configuration cache for this task
        notCompatibleWithConfigurationCache("Uses project.exec and file() at execution time")
        
        // Move problematic property evaluations to doLast to avoid configuration cache issues
        val harmonyAppDir: String by project
        val absoluteHarmonyAppDir = if (File(harmonyAppDir).isAbsolute) harmonyAppDir else
            rootProject.file(harmonyAppDir).absolutePath
            
        into(rootProject.file(absoluteHarmonyAppDir))
        from("build/bin/ohosArm64/${type}Shared/libkn_api.h") {
            into("entry/src/main/cpp/include/")
        }
        from(project.file("build/bin/ohosArm64/${type}Shared/libkn.so")) {
            into("entry/libs/arm64-v8a/")
        }

        outputs.upToDateWhen { false }
        doLast {
            val appJsonContent = file("$absoluteHarmonyAppDir/AppScope/app.json5").readText()
            val bundleNameRegex = """"bundleName"\s*:\s*"([^"]+)"""".toRegex()
            val bundleName = bundleNameRegex.find(appJsonContent)?.groupValues?.get(1) ?: error("bundleName not found")
            val devEcoStudioDir: String by project
            val harmonyAppEntryModuleDir: String by project
            val hFileDir: String by project
            val soFileDir: String by project
            val abilityName: String by project
            val nodeHome = "$devEcoStudioDir/Contents/tools/node"
            val devEcoSdkHome = "$devEcoStudioDir/Contents/sdk"

            if (!File(devEcoStudioDir).exists()) {
                throw GradleException("Provided DevEco Studio install path doesn't exist: $devEcoStudioDir")
            }
            fun execAtHarmonyAppDir(cmd: List<String>) {
                val result = project.exec {
                    environment(mapOf("NODE_HOME" to nodeHome, "DEVECO_SDK_HOME" to devEcoSdkHome))
                    commandLine(cmd)
                    workingDir(absoluteHarmonyAppDir)
                    isIgnoreExitValue = true
                }
                if (result.exitValue != 0) {
                    throw GradleException("${cmd.joinToString(" ")}\nFailed with exit code ${result.exitValue}\nTry to reproduce this in DevEco Studio's command line, otherwise you would need to setup some enviroment parameters")
                }
            }

            println("=== Step 1: Install OHPM dependencies ===")
            execAtHarmonyAppDir(listOf(
                "$devEcoStudioDir/Contents/tools/ohpm/bin/ohpm",
                "install",
                "--all",
                "--registry",
                "https://ohpm.openharmony.cn/ohpm/",
                "--strict_ssl",
                "true"
            ))

            println("=== Step 2: Run hvigor sync ===")
            execAtHarmonyAppDir(listOf(
                "$devEcoStudioDir/Contents/tools/node/bin/node",
                "$devEcoStudioDir/Contents/tools/hvigor/bin/hvigorw.js",
                "--sync",
                "-p", "product=default",
                "-p", "buildMode=${type}",
                "--analyze=false",
                "--parallel",
                "--incremental",
                "--daemon"
            ))

            println("=== Step 3: Build HAP ===")
            execAtHarmonyAppDir(listOf(
                "$devEcoStudioDir/Contents/tools/node/bin/node",
                "$devEcoStudioDir/Contents/tools/hvigor/bin/hvigorw.js",
                "--mode", "module",
                "-p", "module=entry@default",
                "-p", "product=default",
                "-p", "buildMode=${type}",
                "-p", "requiredDeviceType=phone",
                "assembleHap",
                "--analyze=false",
                "--parallel",
                "--incremental",
                "--daemon"
            ))

            println("=== Step 4: Install HAP to device via hdc ===")
            val hapFolder = File("$absoluteHarmonyAppDir/$harmonyAppEntryModuleDir/build/default/outputs/default/")
            val hapFile = File(hapFolder, "entry-default-signed.hap").takeIf { it.exists() } ?: File(hapFolder, "entry-default-unsigned.hap")

            execAtHarmonyAppDir(listOf(
                "$devEcoStudioDir/Contents/sdk/default/openharmony/toolchains/hdc",
                "install",
                hapFile.absolutePath
            ))

            println("=== Step 5: Launch app on device ===")
            println("Ability: $abilityName, Bundle: $bundleName")
            execAtHarmonyAppDir(listOf(
                "$devEcoStudioDir/Contents/sdk/default/openharmony/toolchains/hdc",
                "shell",
                "aa",
                "start",
                "-a", abilityName,
                "-b", bundleName
            ))
        }
    }
}
