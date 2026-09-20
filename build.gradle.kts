import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildDynamic
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kn.clang)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }

    version = System.getenv("GITHUB_REF_NAME") ?: "1.0.0-SNAPSHOT"
    group = "pw.binom"
}

val LUA_SOURCES_DIR = file("${buildFile.parentFile}/src/nativeMain/lua")
val JNI_SOURCES_DIR = file("${buildFile.parentFile}/src/jvmMain/c")

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
        // Wire the per-target POSIX source sets (linux*, androidNative*,
        // mingw*, macos*) onto the shared posixMain / posixTest hierarchy.
        // The binom-publish plugin used to expose a `dependsOn(mask, to)`
        // extension for this; we replace it with a simple wildcard helper
        // because binom-publish is no longer on the classpath.
        listOf("linux", "androidNative", "mingw", "macos").forEach { family ->
            sourceSets.matching { it.name.startsWith(family) && it.name.endsWith("Main") }
                .configureEach { dependsOn(posixMain) }
            sourceSets.matching { it.name.startsWith(family) && it.name.endsWith("Test") }
                .configureEach { dependsOn(posixTest) }
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

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "pw.binom",
        artifactId = "klua",
        version = project.version.toString(),
    )

    pom {
        name.set("klua")
        description.set("Lua 5.4 for Kotlin Multiplatform via JNI on JVM and Kotlin/Native on POSIX/Android targets")
        url.set("https://github.com/caffeine-mgn/klua")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("subochev")
                name.set("Anton Subochev")
                email.set("caffeine.mgn@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/caffeine-mgn/klua.git")
            developerConnection.set("scm:git:ssh://git@github.com/caffeine-mgn/klua.git")
            url.set("https://github.com/caffeine-mgn/klua")
        }
    }
}

/*
 * Apply GPG signing to every Maven publication.
 *
 * Two modes, picked at configuration time by the `signingUseGpg`
 * Gradle property:
 *
 *   signingUseGpg=true — the CI mode used by .github/workflows/release.yml.
 *   The GPG private key is imported into the system keyring once at job
 *   start, and we configure Gradle's `signing` extension to delegate to
 *   the `gpg` binary via `useGpgCmd()`. Key name and passphrase come from
 *   `signing.gnupg.keyName` / `signing.gnupg.passphrase` Gradle properties
 *   (forwarded as `-P` flags from CI).
 *
 *   default (signingUseGpg unset) — local development mode. Read an
 *   in-memory PGP key straight from Gradle properties without ever
 *   touching the system keyring. Two property-naming conventions are
 *   accepted:
 *     - vanniktech standard: `signingInMemoryKey{,Id,Password,IsBase64}`;
 *     - binom convention:    `binom.gpg.{private_key,key_id,password}`.
 *   `signingInMemoryKey*` wins when both are present. The private key
 *   value is assumed ASCII-armored with literal "\n" escapes (or
 *   base64-encoded when `signingInMemoryKeyIsBase64=true`); either way
 *   it is normalised into the real PGP block before being handed to
 *   `useInMemoryPgpKeys`.
 */
pluginManager.withPlugin("signing") {
    if (findProperty("signingUseGpg") == "true") {
        extensions.configure<SigningExtension>("signing") {
            useGpgCmd()
        }
        logger.lifecycle("[signing] Using system gpg via signing.gnupg.keyName=${findProperty("signing.gnupg.keyName")}")
        return@withPlugin
    }

    val key = providers.gradleProperty("signingInMemoryKey").orNull
        ?: providers.gradleProperty("binom.gpg.private_key").orNull
    val keyId = providers.gradleProperty("signingInMemoryKeyId").orNull
        ?: providers.gradleProperty("binom.gpg.key_id").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
        ?: providers.gradleProperty("binom.gpg.password").orNull
    val isBase64 = providers.gradleProperty("signingInMemoryKeyIsBase64").orNull?.toBoolean() ?: false

    if (key != null && keyId != null && password != null) {
        val decodedKey = if (isBase64) {
            String(Base64.getDecoder().decode(key))
        } else {
            // Both `signingInMemoryKey` and `binom.gpg.private_key` are
            // typically stored as ASCII-armored with literal "\n" escapes;
            // turn them into real newlines before handing to PGP.
            key.replace("\\n", "\n")
        }
        logger.lifecycle("[signing] Using in-memory PGP key, length=${decodedKey.length}, isBase64=${isBase64}")
        extensions.getByType(SigningExtension::class.java)
            .useInMemoryPgpKeys(decodedKey, keyId, password)
    } else {
        logger.lifecycle("[signing] No in-memory PGP key configured; publications will be signed by the publishing plugin only.")
    }
}
