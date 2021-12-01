import org.jetbrains.kotlin.konan.target.KonanTarget
import pw.binom.eachKotlinNativeCompile
import pw.binom.kotlin.clang.*
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

apply<pw.binom.plugins.BinomPublishPlugin>()

val luaPackageName = "platform.internal_lua"
fun getLinkArgs(target: KotlinNativeTarget) =
        listOf("-include-binary", file("${buildDir}/native/static/${target.konanTarget.name}/liblua.a").absolutePath)

fun KotlinNativeTarget.configNative() {
    binaries {
        compilations["main"].cinterops {
            create("lua") {
                defFile = project.file("src/nativeInterop/lua.def")
                packageName = luaPackageName
                includeDirs.headerFilterOnly("${buildFile.parent}/src/nativeMain/lua")
            }
        }
        compilations["main"].kotlinOptions.freeCompilerArgs = getLinkArgs(target)
        compilations["test"].kotlinOptions.freeCompilerArgs = getLinkArgs(target)
    }
}

kotlin {
    jvm()
    linuxX64 { // Use your target instead.
        binaries {
            staticLib()
        }
//        configNative()
    }
    linuxArm32Hfp {
        binaries {
            staticLib()
        }
//        configNative()
    }
    linuxArm64 {
        binaries {
            staticLib()
        }
//        configNative()
    }
    mingwX64 { // Use your target instead.
        binaries {
            staticLib()
        }
//        configNative()
    }
    mingwX86 { // Use your target instead.
        binaries {
            staticLib()
        }
//        configNative()
    }

    androidNativeArm32 {
        binaries {
            staticLib()
        }
//        configNative()
    }
    androidNativeArm64 {
        binaries {
            staticLib()
        }
//        configNative()
    }
    androidNativeX86 {
        binaries {
            staticLib()
        }
//        configNative()
    }
    androidNativeX64 {
        binaries {
            staticLib()
        }
//        configNative()
    }
    macosX64 {
        binaries {
            framework()
        }
//        configNative()
    }
    eachNative {
        val luaTask = clangBuildStatic(target = konanTarget, name = "lua") {
            compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3")
            compileDir(
                    sourceDir = file("${buildFile.parentFile}/src/nativeMain/lua"),
            )
        }
        tasks.findByName(compileTaskName)?.dependsOn(luaTask)
        binaries {
            compilations["main"].cinterops {
                create("lua") {
                    defFile = project.file("src/nativeInterop/lua.def")
                    packageName = luaPackageName
                    includeDirs.headerFilterOnly("${buildFile.parent}/src/nativeMain/lua")
                }
            }
            compilations["main"].addStatic(luaTask.staticFile)
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

//        nativeMain {
//            dependencies {
//                dependsOn commonMain
//            }
//        }

        val linuxX64Main by getting {
            dependencies {
                dependsOn(commonMain)
            }
        }
        val linuxX64Test by getting {

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

        val mingwX64Test by getting {
            dependencies {
                dependsOn(linuxX64Test)
            }
        }

        val mingwX86Main by getting {
            dependencies {
                dependsOn(linuxX64Main)
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

//fun defineBuild(selectTarget: KonanTarget): BuildStaticTask {
//    val task = tasks.create("buildLua${selectTarget.name.capitalize()}", BuildStaticTask::class.java)
//    task.target = selectTarget
//    task.group = "build"
//    task.compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3")
//    task.compileDir(
//        sourceDir = file("${buildFile.parentFile}/src/nativeMain/lua"),
//        objectDir = file("${buildDir}/native/o/${selectTarget.name}"),
//        args = null,
//        filter = null
//    )
//    task.staticFile.set(file("${buildDir}/native/static/${selectTarget.name}/liblua.a"))
//    return task
//}
//tasks {
//    eachKotlinNativeCompile {
//        val buildTask = defineBuild(KonanTarget.predefinedTargets[it.target]!!)
//        it.dependsOn(buildTask)
//    }
//}

apply<pw.binom.plugins.DocsPlugin>()