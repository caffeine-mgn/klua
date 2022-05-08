import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import pw.binom.BuildBinaryWasm32
import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import pw.binom.plugins.HttpServerTask
import pw.binom.publish.binom
import pw.binom.publish.ifNotMac
import pw.binom.publish.plugins.*

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
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
val jsRun = System.getProperty("jsrun") != null
kotlin {
    jvm()
    ifNotMac {
        linuxX64()
        linuxArm32Hfp()
        linuxArm64()
        linuxMips32()
        linuxMipsel32()
        mingwX64()
        mingwX86()
        androidNativeArm32()
        androidNativeArm64()
        androidNativeX86()
        androidNativeX64()
        wasm32()
    }
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
            js(BOTH) {
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
//        val commonNativeLikeMain by creating {
//            dependsOn(commonMain)
//        }
//
//        val commonNativeLikeTest by creating {
//            dependsOn(commonTest)
//        }

        val linuxX64Main by getting {
            dependencies {
                dependsOn(commonMain)
            }
        }

        val linuxX64Test by getting {
            dependencies {
                dependsOn(commonTest)
            }
        }

        val linuxArm32HfpMain by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }
        val linuxArm64Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }

        val macosX64Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }

        val mingwX64Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }
        val mingwX64Test by getting {
            dependencies {
                dependsOn(linuxX64Test)
            }
        }
        val androidNativeArm32Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }
        val androidNativeArm64Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }
        val androidNativeX86Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }
        val androidNativeX64Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }
        val mingwX86Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }
        val wasm32Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
            }
        }
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
                api("org.luaj:luaj-jse:3.0.1")
            }
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

tasks {
    if (pw.binom.Config.JS_TARGET_SUPPORT) {
        val linkTask = register("linkBinaryLuaWasm32", BuildBinaryWasm32::class.java)
        val linkTask1 = register("linkBinaryLuaWasm32SingleFile", BuildBinaryWasm32::class.java)
        linkTask.configure {
            this.output.set(buildDir.resolve("native/lua/wasm32/binary/lua_native.js"))
        }
        linkTask1.configure {
            this.output.set(buildDir.resolve("native/lua/wasm32/binary/lua_native_single.js"))
            this.customArgs.add("-s")
            this.customArgs.add("SINGLE_FILE=1")
        }
        listOf(linkTask, linkTask1).forEach {
            it.run {
                configure {
                    fun strConfig(name: String, value: String) {
                        this.customArgs.add("-s")
                        this.customArgs.add("$name=$value")
                    }

                    val tmpFile = File.createTempFile("postjs", "js")
                    val KLUA_CPP_SOURCES_DIR = file("src/nativeMain/klua")
                    fun strConfig(name: String, value: Int) = strConfig(name = name, value = value.toString())
                    group = "build"
                    cppFiles.from(fileTree(LUA_SOURCES_DIR).filter { it.extension != "h" && it.extension != "hpp" && it.name != "luac.c" })
                    cppFiles.from(fileTree(KLUA_CPP_SOURCES_DIR))
                    this.customArgs.add("-DLUA_COMPAT_5_3")
                    this.customArgs.add("-DLUA_BUILD_AS_DLL")
                    this.customArgs.add("-g0")
                    this.customArgs.add("-O0")
                    this.customArgs.add("-fno-rtti")
                    this.customArgs.add("--post-js")
                    this.customArgs.add(tmpFile.absolutePath)
                    strConfig("INVOKE_RUN", "0")
                    strConfig("EXPORT_NAME", "LuaNative")
                    strConfig("MODULARIZE", 1)
                    strConfig("NO_FILESYSTEM", 1)
                    strConfig("ABORTING_MALLOC", 0)
                    strConfig("ALLOW_TABLE_GROWTH", 1)
                    strConfig("ERROR_ON_UNDEFINED_SYMBOLS", 0)
                    strConfig("SUPPORT_ERRNO", 0)
                    strConfig("ALLOW_MEMORY_GROWTH", 1)
                    strConfig("SAFE_HEAP", 0)
                    strConfig("JS_MATH", 1)
                    strConfig("ASSERTIONS", 0)
                    strConfig("FETCH_SUPPORT_INDEXEDDB", 0)
                    strConfig("FETCH", 0)
                    this.customArgs.add("-flto") // Enables link-time optimizations (LTO).

                    doFirst {
                        tmpFile.writeText("Module['addFunction']=addFunction;Module['removeFunction']=removeFunction;")
                    }
                    doLast {
                        tmpFile.delete()
                    }
                }
            }
        }

        if (jsRun) {
            val jsTestClasses by getting {
//        dependsOn(generateWasmTestingSource)
            }

            val jsProcessResources by getting {
//            dependsOn(linkTask)
            }

            val jsTest by getting {
//        dependsOn(appendTestData)
                onlyIf { false }
            }

            val jsBrowserDevelopmentRun by getting(KotlinWebpack::class) {
                this.devServer?.open = false
            }
        }

        val testingServer by creating(HttpServerTask::class.java) {
            root(linkTask.get().output.get().parentFile)
            port(8093)
            dependsOn(linkTask)
            dependsOn(linkTask1)
        }
        if (jsRun) {
            val jsBrowserTest by getting {
                testingServer.runDuringTask(this)
            }
        } else {
            val jsLegacyBrowserTest by getting {
                testingServer.runDuringTask(this)
            }
            val jsIrBrowserTest by getting {
                testingServer.runDuringTask(this)
            }
        }
    }
}
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
