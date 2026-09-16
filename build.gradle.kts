import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildDynamic
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import pw.binom.publish.allTargets
import pw.binom.publish.binom
import pw.binom.publish.dependsOn
import pw.binom.publish.ifNotMac
import pw.binom.publish.plugins.*
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
    id("pw.binom.kn-clang") version "0.0.5"
}

allprojects {
    version = System.getenv("GITHUB_REF_NAME") ?: "1.0.0-SNAPSHOT"
    group = "pw.binom"

    repositories {
        binom()
        mavenLocal()
        mavenCentral()
    }
}

val LUA_SOURCES_DIR = file("${buildFile.parentFile}/src/nativeMain/lua")
val JNI_SOURCES_DIR = file("${buildFile.parentFile}/src/jvmMain/c")
val jsRun = System.getProperty("jsrun") != null

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("started", "passed", "failed", "skipped", "standardOut", "standardError")
    }
}
kotlin {
    jvm()
    linuxX64()
    linuxArm64()
    mingwX64()
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()
    macosX64()
//    macosArm64()
//    ios()
//    iosArm32()
//    iosArm64()
//    iosSimulatorArm64()
//    watchos()
//    watchosArm32()
//    watchosArm64()
//    watchosSimulatorArm64()
//    watchosX86()
//    watchosX64()
    targets {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }
//    allTargets {
//        compilerOptions {
//            freeCompilerArgs.add("-Xexpect-actual-classes")
//        }
//    }
    if (pw.binom.Config.JS_TARGET_SUPPORT) {
        if (jsRun) {
            js("js") {
                browser {
                    testTask {
                        useKarma {
                            useFirefox()
//                        useFirefoxHeadless()
//                        useChromium()
                        }
                    }
                }
                binaries.executable()
            }
        } else {
            var applled = false
            js(IR) {
                browser {
                    browser {
                        testTask {
                            if (!applled) {
                                applled = true
                                useKarma {
                                    useChromiumHeadless()
//                                useFirefoxHeadless()
                                }
                            }
                        }
                    }
                }
                nodejs()
            }
        }
    }

    eachNative {
        val buildLuaTask = clangBuildStatic(target = konanTarget, name = "lua") {
            konanVersion.set("2.4.20")
            compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3")
            compileDir(
                sourceDir = LUA_SOURCES_DIR,
            )
        }
        tasks.findByName(compileTaskName)?.dependsOn(buildLuaTask)
        binaries {
            compilations["main"].apply {
                addStatic(buildLuaTask.staticFile)
                cinterops {
                    create("lua") {
                        defFile = project.file("src/nativeInterop/lua.def")
                        packageName = "platform.internal_lua"
                        includeDirs.headerFilterOnly(LUA_SOURCES_DIR)
                    }
                }
            }
        }
    }

    /*
     * Build Lua 5.4 + klua_jni.c as a dynamic library for each JVM host via clangBuildDynamic.
     * Output naming: build/native/klua/<target>/dynamic/libklua.{so,dylib,dll}.
     *
     * Targets are registered eagerly so the build graph is consistent across platforms;
     * the actual cross-compile step is what runs (or skips) per host. Linux/Windows targets
     * require the corresponding cross-toolchain on the build host; macOS targets only
     * compile when running on macOS (Apple's clang doesn't cross-compile from Linux/Windows
     * without SDK hacking, so we don't bother).
     */
    val currentHost = org.jetbrains.kotlin.konan.target.HostManager.host
    val isMacHost = currentHost == org.jetbrains.kotlin.konan.target.KonanTarget.MACOS_X64 ||
            currentHost == org.jetbrains.kotlin.konan.target.KonanTarget.MACOS_ARM64
    val jvmHostTargets = buildList {
        add(org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64)
        add(org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_ARM64)
        add(org.jetbrains.kotlin.konan.target.KonanTarget.MINGW_X64)
        if (isMacHost) {
            add(currentHost)
        }
    }
    val jvmBuildTasks = jvmHostTargets.associateWith { target ->
        // Platform-specific JNI include: JAVA_HOME/include/<linux|darwin|win32>
        val platform = when (target.family) {
            org.jetbrains.kotlin.konan.target.Family.LINUX,
            org.jetbrains.kotlin.konan.target.Family.ANDROID -> "linux"
            org.jetbrains.kotlin.konan.target.Family.OSX -> "darwin"
            org.jetbrains.kotlin.konan.target.Family.MINGW -> "win32"
            else -> "linux"
        }
        val jdkHome = System.getenv("JAVA_HOME")
        val jdkIncludeCandidates = listOfNotNull(
            jdkHome?.let { "$it/include" },
            "/usr/lib/jvm/java-21-openjdk/include",
            "/usr/lib/jvm/default-java/include",
        )
        val jdkInclude = jdkIncludeCandidates.firstOrNull { File(it).exists() } ?: ""
        val jdkIncludePlatformCandidates = listOfNotNull(
            jdkHome?.let { "$it/include/$platform" },
            "/usr/lib/jvm/java-21-openjdk/include/$platform",
            "/usr/lib/jvm/default-java/include/$platform",
        )
        val jdkIncludePlatform = jdkIncludePlatformCandidates.firstOrNull { File(it).exists() } ?: ""
        clangBuildDynamic(target = target, name = "klua") {
            konanVersion.set("2.4.20")
            compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3", "-fno-rtti")
            include(LUA_SOURCES_DIR)
            if (jdkInclude.isNotEmpty()) include(File(jdkInclude))
            if (jdkIncludePlatform.isNotEmpty()) include(File(jdkIncludePlatform))
            compileDir(sourceDir = LUA_SOURCES_DIR)
            compileDir(sourceDir = JNI_SOURCES_DIR)
        }.also { dynamicTask ->
            // Cross-targets gracefully no-op when the host can't build them: llvm-as
            // and the platform-specific JDK headers (jni_md.h) aren't always present.
            // Keeping the task in the graph means `gradle tasks` / IDEs see the full
            // target list; on capable hosts the real cross-compile happens automatically.
            val needsCrossCompile = target != currentHost
            if (needsCrossCompile) {
                val jdkIncludeOk = jdkInclude.isNotEmpty() && jdkIncludePlatform.isNotEmpty()
                dynamicTask.onlyIf("${target.name} build host prerequisites") {
                    jdkIncludeOk
                }
            }
        }
    }
    val jvmCopyTasks = jvmBuildTasks.mapValues { (target, buildTask) ->
        val libExt = when (target.family) {
            org.jetbrains.kotlin.konan.target.Family.MINGW -> "dll"
            org.jetbrains.kotlin.konan.target.Family.OSX,
            org.jetbrains.kotlin.konan.target.Family.IOS,
            org.jetbrains.kotlin.konan.target.Family.TVOS,
            org.jetbrains.kotlin.konan.target.Family.WATCHOS -> "dylib"
            else -> "so"
        }
        // clangBuildDynamic outputs to "klua.<ext>" (no "lib" prefix). We also produce a
        // "libklua.<ext>" copy so platforms that prefer soname-style naming are happy;
        // NativeLoader picks the right one based on the host.
        val srcFileName = "klua.$libExt"
        val libFileName = "libklua.$libExt"
        val outDir = layout.buildDirectory.dir("processed-resources/native/${target.name}").get().asFile
        val copyTask = tasks.register("copyKluaNativeLib${target.name}", Copy::class.java) {
            from(buildTask.dynamicFile)
            rename { libFileName }
            into(outDir)
            outputs.upToDateWhen { true }
        }
        copyTask to srcFileName
    }

    sourceSets {

        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib-common:${pw.binom.Versions.KOTLIN_VERSION}")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val posixMain by creating {
            dependsOn(commonMain)
        }
        val posixTest by creating {
            dependsOn(commonTest)
        }
        dependsOn("linux*Main", posixMain)
        dependsOn("androidNative*Main", posixMain)
        dependsOn("mingw*Main", posixMain)
        dependsOn("macos*Main", posixMain)

        dependsOn("linux*Test", posixTest)
        dependsOn("androidNative*Test", posixTest)
        dependsOn("mingw*Test", posixTest)
        dependsOn("macos*Test", posixTest)

        if (pw.binom.Config.JS_TARGET_SUPPORT) {
            val jsMain by getting {
                dependencies {
                    api(kotlin("stdlib-js"))
                    dependsOn(commonMain)
                }
            }

            val jsTest by getting {
                dependencies {
                    api(kotlin("test-js"))
                    dependsOn(commonTest)
                }
            }
        }
        val jvmMain by getting {
            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib:${pw.binom.Versions.KOTLIN_VERSION}")
                // luaj-jse removed; Lua is now provided by the bundled native library
                // built from the same Lua 5.4 sources used by Kotlin/Native targets.
            }
        }
        tasks.named("jvmProcessResources", Copy::class.java).configure {
            dependsOn(jvmCopyTasks.values.map { it.first })
            from(layout.buildDirectory.dir("processed-resources/native"))
            include("**/*.so", "**/*.dylib", "**/*.dll")
        }

        val jvmTest by getting {
            dependencies {
                api(kotlin("test-junit"))
            }
        }
    }
}

// val c = clangBuildStatic(target = org.jetbrains.kotlin.konan.target.KonanTarget.WASM32, name = "lua") {
//    compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3")
//    compileDir(
//        sourceDir = LUA_SOURCES_DIR,
//    )
// }

// JS / WASM build pipeline disabled (Config.JS_TARGET_SUPPORT = false).
// Original implementation removed; see git history if you need to revive it.
apply<pw.binom.publish.plugins.PrepareProject>()

extensions.getByType(pw.binom.publish.plugins.PublicationPomInfoExtension::class).apply {
    useApache2License()
    gitScm("https://github.com/caffeine-mgn/klua")
    author(
        id = "subochev",
        name = "Anton Subochev",
        email = "caffeine.mgn@gmail.com"
    )
}
