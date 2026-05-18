import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

abstract class FixSourceSetPathMapTask : DefaultTask() {
    @get:InputFile
    abstract val mappingFile: RegularFileProperty

    @TaskAction
    fun fix() {
        val file = mappingFile.get().asFile
        if (!file.exists()) return

        val originalLines = file.readLines()
        val mergedAliasLines = buildList {
            originalLines.forEach { line ->
                add(line)
                when {
                    line.contains(".app-packageDebugResources-59 ") -> {
                        add(line.replace(".app-packageDebugResources-59 ", ".app-mergeDebugResources-59 "))
                    }

                    line.contains(".app-packageDebugResources-60 ") -> {
                        add(line.replace(".app-packageDebugResources-60 ", ".app-mergeDebugResources-60 "))
                    }
                }
            }
        }.distinct()

        if (mergedAliasLines != originalLines) {
            file.writeText(mergedAliasLines.joinToString(System.lineSeparator()))
        }
    }
}

android {
    namespace = "com.tutu.myblbl"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tutu.myblbl"
        minSdk = 23
        targetSdk = 35
        versionCode = 33
        versionName = "1.3.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        disable += setOf(
            "ResourceCycle",
            "MissingDefaultResource",
            "PxUsage",
            "UnusedResources",
            "PrivateResource",
            "NotifyDataSetChanged",
            "RtlHardcoded",
            "RtlSymmetry",
            "HardcodedText",
            "Overdraw",
            "VectorPath",
            "GradleDependency",
            "DrawAllocation",
            "SpUsage",
            "OldTargetApi",
            "DiscouragedApi",
            "IconXmlAndPng",
            "IconLauncherShape",
            "PluralsCandidate",
            "UnusedAttribute",
            "TypographyDashes",
            "UnclosedTrace",
            "ObsoleteSdkInt",
            "UseKtx",
            "ChromeOsAbiSupport",
            "GifUsage",
            "IconMissingDensityFolder",
            "InsecureBaseConfiguration",
            "VectorRaster",
            "UnsafeOptInUsageError"
        )
        fatal += setOf("NotSibling")
    }
}

abstract class RenameApkTask : DefaultTask() {
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val renamedApkDirectory: DirectoryProperty

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @TaskAction
    fun rename() {
        val sourceApks = apkDirectory.get().asFileTree
            .matching { include("**/*.apk") }
            .files
            .sortedBy { it.name }

        if (sourceApks.isEmpty()) {
            error("No APK outputs found in ${apkDirectory.get().asFile}")
        }

        val outputDir = renamedApkDirectory.get().asFile.apply { mkdirs() }
        outputDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("apk", ignoreCase = true)) {
                file.delete()
            }
        }

        sourceApks.forEachIndexed { index, apk ->
            val suffix = if (sourceApks.size == 1) "" else "-${index + 1}"
            val outputFile = File(
                outputDir,
                "${appName.get()}-v${versionName.get()}-${buildType.get()}$suffix.apk"
            )
            apk.copyTo(outputFile, overwrite = true)
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val capitalizedVariantName = variant.name.replaceFirstChar { firstChar ->
            if (firstChar.isLowerCase()) {
                firstChar.titlecase()
            } else {
                firstChar.toString()
            }
        }
        val renameTask = tasks.register("rename${capitalizedVariantName}Apk", RenameApkTask::class) {
            apkDirectory.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            renamedApkDirectory.set(layout.buildDirectory.dir("outputs/renamed_apk/${variant.name}"))
            appName.set("MyBili")
            buildType.set(variant.buildType)
            versionName.set(variant.outputs.single().versionName)
        }
        tasks.register("assemble${capitalizedVariantName}Renamed") {
            group = "build"
            description = "Builds the ${variant.name} APK and copies it to the renamed output directory."
            dependsOn("assemble${capitalizedVariantName}")
            dependsOn(renameTask)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("io.insert-koin:koin-android:3.5.3")

    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-ui:1.9.3")
    implementation("androidx.media3:media3-common:1.9.3")
    implementation("androidx.media3:media3-datasource:1.9.3")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.3")
    implementation("androidx.media3:media3-database:1.9.3")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.3")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.3")

    // Coil 3.x：Kotlin/Coroutine 原生图片加载，默认走 OkHttp，无需注解处理器。
    implementation(platform("io.coil-kt.coil3:coil-bom:3.0.4"))
    implementation("io.coil-kt.coil3:coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp")
    implementation("io.coil-kt.coil3:coil-gif")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Android TV
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    // Protobuf (弹幕解析)
    implementation("com.google.protobuf:protobuf-javalite:3.24.0")

    // 官方弹幕引擎源码已内嵌在工程中。
    // Keep gdx core for utility classes used by the embedded danmaku engine.
    implementation("com.badlogicgames.gdx:gdx:1.10.0")
    implementation("com.badlogicgames.ashley:ashley:1.7.3")

    // 二维码解析
    implementation("com.google.zxing:core:3.5.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}


val fixDebugSourceSetPathMap by tasks.registering(FixSourceSetPathMapTask::class) {
    val mapTaskName = "mapDebugSourceSetPaths"
    dependsOn(mapTaskName)
    mappingFile.set(
        layout.buildDirectory.file(
            "intermediates/source_set_path_map/debug/$mapTaskName/file-map.txt"
        )
    )
}

tasks.matching { it.name == "mergeDebugResources" }.configureEach {
    dependsOn(fixDebugSourceSetPathMap)
}
