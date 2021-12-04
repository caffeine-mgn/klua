import pw.binom.kotlin.clang.*
import pw.binom.*
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.eachNative
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

apply<pw.binom.plugins.BinomPublishPlugin>()
val LUA_SOURCES_DIR = file("${buildFile.parentFile}/src/nativeMain/lua")

kotlin {
    jvm()
    linuxX64 {
        binaries {
            staticLib()
        }
    }
    linuxArm32Hfp {
        binaries {
            staticLib()
        }
    }
    linuxArm64 {
        binaries {
            staticLib()
        }
    }
    mingwX64 {
        binaries {
            staticLib()
        }
    }
    mingwX86 {
        binaries {
            staticLib()
        }
    }

    androidNativeArm32 {
        binaries {
            staticLib()
        }
    }
    androidNativeArm64 {
        binaries {
            staticLib()
        }
    }
    androidNativeX86 {
        binaries {
            staticLib()
        }
    }
    androidNativeX64 {
        binaries {
            staticLib()
        }
    }
    macosX64 {
        binaries {
            framework()
        }
    }
    js("js" /*BOTH*/) {
        browser {
            testTask {
                useKarma {
                    useFirefoxHeadless()
                }
            }
        }
        binaries.executable()
//        nodejs()
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
        val commonNativeLikeMain by creating {
            dependsOn(commonMain)
        }

        val linuxX64Main by getting {
            dependencies {
                dependsOn(commonNativeLikeMain)
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
        val jsMain by getting {
            dependencies {
                api(kotlin("stdlib-js"))
                dependsOn(commonNativeLikeMain)
            }
        }

        val jsTest by getting {
            dependencies {
                api(kotlin("test-js"))
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

allprojects {
    version = pw.binom.Versions.LIB_VERSION
    group = "pw.binom"

    repositories {
        mavenLocal()
        mavenCentral()
    }
}

//val c = clangBuildStatic(target = org.jetbrains.kotlin.konan.target.KonanTarget.WASM32, name = "lua") {
//    compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3")
//    compileDir(
//        sourceDir = LUA_SOURCES_DIR,
//    )
//}

tasks {
    val linkTask = register("linkBinaryLuaWasm32", BuildBinaryWasm32::class.java)
    linkTask.configure {
        fun strConfig(name: String, value: String) {
            this.customArgs.add("-s")
            this.customArgs.add("$name=$value")
        }

        fun strConfig(name: String, value: Int) = strConfig(name = name, value = value.toString())
        group = "build"
        this.cppFiles.from(fileTree(LUA_SOURCES_DIR).filter { it.extension != "h" && it.extension != "hpp" && it.name != "luac.c" })
//        this.customArgs.add("-s")
//        this.customArgs.add("EXPORT_ALL=1")
//        this.customArgs.add("-s")
//        this.customArgs.add("-s")
//        customArgs.add("EXPORTED_FUNCTIONS")
        this.customArgs.add("-DLUA_COMPAT_5_3")
        this.customArgs.add("-DLUA_BUILD_AS_DLL")
//        this.customArgs.add("-DLUA_LIB")
//        this.customArgs.add("-fdeclspec")
        this.customArgs.add("-g0")
        this.customArgs.add("-O3")
        strConfig("ENVIRONMENT", "web")
        strConfig("EXPORT_NAME", "KLuaWasm")
        strConfig("MODULARIZE", 1)
//
//        strConfig("MINIMAL_RUNTIME", 1)
        strConfig("SUPPORT_ERRNO", 0)
//        strConfig("ALLOW_MEMORY_GROWTH", 1)
//        strConfig("SAFE_HEAP", 1)
//        strConfig("JS_MATH", 1)
//        strConfig("ASSERTIONS", 1)


//        this.customArgs.add("--extern-post-js")
//        this.customArgs.add(buildDir.resolve("test.js").absolutePath)
//        this.customArgs.add("---DLUA_COMPAT_5_3")
//        this.customArgs.add("--cflags")
        this.customArgs.add("-flto")//Enables link-time optimizations (LTO).
//        this.customArgs.add("-D__EMSCRIPTEN__=1")
//        this.cppFiles.from(c.staticFile)
        this.output.set(buildDir.resolve("native/lua/wasm32/binary/lua.js"))
    }

    val copyWasm by creating(Copy::class.java) {
        from(linkTask.get().output.get().asFile.parentFile)
        destinationDir = buildDir.resolve("processedResources/js/main")
        dependsOn(linkTask)
    }
    val jsProcessResources by getting {
        dependsOn(copyWasm)
    }

    val jsBrowserDevelopmentRun by getting(KotlinWebpack::class) {
        this.devServer?.open = false
    }
}

apply<pw.binom.plugins.DocsPlugin>()