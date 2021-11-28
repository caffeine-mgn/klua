import org.jetbrains.kotlin.konan.target.KonanTarget
import pw.binom.eachKotlinCompile
import pw.binom.eachKotlinNativeCompile
import pw.binom.kotlin.clang.BuildStaticTask

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

apply {
    plugin(pw.binom.plugins.BinomPublishPlugin::class.java)
}
val luaPackageName = "platform.internal_lua"
fun getLinkArgs(target: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget) =
    listOf("-include-binary", file("${buildDir}/native/${target.konanTarget.name}/liblua.a").absolutePath)

kotlin {
    linuxX64 { // Use your target instead.
        binaries {
            staticLib()
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
    jvm {
        compilations.all {
            kotlinOptions {
//                jvmTarget = "11"
            }
        }
    }
    if (pw.binom.Target.LINUX_ARM32HFP_SUPPORT) {
        linuxArm32Hfp {
            binaries {
                staticLib()
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
    }

    mingwX64 { // Use your target instead.
        binaries {
            staticLib()
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
    if (pw.binom.Target.MINGW_X86_SUPPORT) {
        mingwX86 { // Use your target instead.
            binaries {
                staticLib()
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
    }
    if (pw.binom.Target.LINUX_ARM64_SUPPORT) {
        linuxArm64 {
            binaries {
                staticLib()
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
    }
    if (pw.binom.Target.LINUX_ARM32HFP_SUPPORT) {
        linuxArm32Hfp {
            binaries {
                staticLib()
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
    }

    macosX64 {
        binaries {
            framework {
            }
            compilations["main"].cinterops {
                create("lua") {
                    defFile = project.file("src/nativeInterop/nativeSqlite3.def")
                    packageName = luaPackageName
                    includeDirs.headerFilterOnly("${buildFile.parent}/src/nativeMain/lua")
                }
            }
            compilations["main"].kotlinOptions.freeCompilerArgs = getLinkArgs(target)
            compilations["test"].kotlinOptions.freeCompilerArgs = getLinkArgs(target)
        }
    }

    sourceSets {

        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib-common:${pw.binom.Versions.KOTLIN_VERSION}")
//                api(project(":core"))
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

        if (pw.binom.Target.LINUX_ARM32HFP_SUPPORT) {
            val linuxArm32HfpMain by getting {
                dependencies {
                    dependsOn(linuxX64Main)
                }
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

        if (pw.binom.Target.MINGW_X86_SUPPORT) {
            val mingwX86Main by getting {
                dependencies {
                    dependsOn(linuxX64Main)
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

allprojects {
    version = pw.binom.Versions.LIB_VERSION
    group = "pw.binom"

    repositories {
        mavenLocal()
        mavenCentral()
    }
}

fun defineBuild(selectTarget: KonanTarget):BuildStaticTask {
    val task = tasks.create("buildLua${selectTarget.name.capitalize()}", BuildStaticTask::class.java)
    task.target = selectTarget
    task.group = "build"
    task.compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3")
    task.compileDir(
        sourceDir = file("${buildFile.parentFile}/src/nativeMain/lua"),
        objectDir = file("${buildDir}/native/o/${selectTarget.name}"),
        args = null,
        filter = null
    )
    task.staticFile = file("${buildDir}/native/${selectTarget.name}/liblua.a")
    return task
}
tasks {
    eachKotlinNativeCompile {
        val buildTask = defineBuild(KonanTarget.predefinedTargets[it.target]!!)
        it.dependsOn(buildTask)
    }
}

apply<pw.binom.plugins.DocsPlugin>()