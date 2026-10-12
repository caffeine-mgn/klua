import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildDynamic
import pw.binom.kotlin.clang.clangBuildJni
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileDirJni
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import pw.binom.kotlin.clang.konan.jdkIncludePlatform
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.net.URI
import java.util.Base64

plugins {
    // AGP + KGP both come from the buildSrc buildscript classpath (see
    // buildSrc/build.gradle.kts) so KGP's AndroidGradlePluginVersion detection
    // can see AGP; hence the versionless plugin id here.
    id("com.android.library")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kn.clang)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    // Release tags drive the version; `-Pversion=` overrides it for dry runs.
    version = providers.gradleProperty("version").orNull
        ?: System.getenv("GITHUB_REF_NAME")
        ?: "1.0.0-SNAPSHOT"
    group = "pw.binom"
}

val LUA_SOURCES_DIR = file("${buildFile.parentFile}/src/nativeMain/lua")
// Shared execution-limits C core (custom allocator + instruction/timeout/cancel
// hook). Compiled into every native backend: Kotlin/Native static lib and the
// JNI dynamic libraries (JVM jar + Android AAR).
val LIMITS_SOURCES_DIR = file("${buildFile.parentFile}/src/nativeMain/limits")
// JNI bridge location: klua_jni.c. Resolved by BuildDynamicTask.compileDirJni()
// which defaults to this KMP-conventional path, so we no longer need a constant
// for it on the JVM/JNI side. (Android AARs have their own convention below.)
val ANDROID_JNI_LIBS_DIR = layout.buildDirectory.dir("androidJniLibs")
// Copy tasks that populate ANDROID_JNI_LIBS_DIR; wired into AGP's preBuild below.
val androidNativeCopyTasks = mutableListOf<TaskProvider<*>>()

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("started", "passed", "failed", "skipped", "standardOut", "standardError")
    }
}
kotlin {
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    linuxX64()
    linuxArm64()
    mingwX64()
    androidNativeArm64()
    // Tier-3 32-bit Android NDK targets. These compile now that the
    // LuaEngine.eval() call site uses klua_loadstring (a C wrapper that
    // takes a NUL-terminated string and computes strlen internally)
    // instead of calling luaL_loadbufferx directly — that wrapper avoids
    // the size_t/ULong/UInt mismatch that Kotlin/Native cinterop has
    // when size_t maps to ULong on 64-bit K/N and UInt on 32-bit K/N.
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()
    // macosX64 is deprecated since Kotlin 2.3.20 (scheduled for removal); the
    // supported macOS target is macosArm64, and the GitHub macOS runner is
    // arm64-only anyway.
    macosArm64()
    // Apple mobile: iOS device + Apple-Silicon simulator. The macos-14 CI
    // runner carries Xcode with the iOS simulator runtime, so we can build
    // and test these on every release. We skip the deprecated Intel-sim
    // targets (iosX64, watchosX64, tvosX64) and the deprecated 32-bit
    // device targets (iosArm32, watchosArm32, watchosX86) — they are
    // scheduled for removal in the Kotlin/Native tier plan
    // (https://kotl.in/native-targets-tiers).
    iosArm64()
    iosSimulatorArm64()
    // watchOS: device + Apple-Silicon simulator. Same Xcode-derived
    // toolchain as iOS, so it builds on the macos-14 runner for free. We
    // register the ILP32-ABI `watchosArm64` (the original watchOS device
    // target) plus the Apple-Silicon ILP32 sim; the LP64 `watchosDeviceArm64`
    // target (introduced for watchOS 9+ on Apple Watch Series 9+ hardware)
    // is intentionally not registered because the Xcode 26.4 watchOS SDK
    // it ships with does not expose `errno.h` (or any of the POSIX
    // `<errno.h>`-derived headers), which Lua's lauxlib.c includes
    // unconditionally. There is no clean workaround at the application
    // level — patching upstream Lua to conditionally include `<errno.h>`
    // would be intrusive and would not survive an upstream upgrade. watchOS
    // hardware running the LP64 ABI is still covered by the ILP32
    // `watchosArm64` target, which uses a different sysroot and ships a
    // full POSIX header set.
    watchosArm64()
    watchosSimulatorArm64()
    // tvOS: device + Apple-Silicon simulator. Same Xcode toolchain.
    tvosArm64()
    tvosSimulatorArm64()
    targets {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    eachNative {
        val buildLuaTask = clangBuildStatic(target = konanTarget, name = "lua") {
            konanVersion.set("2.4.20")
            compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3")
            include(LUA_SOURCES_DIR)
            include(LIMITS_SOURCES_DIR)
            compileDir(
                sourceDir = LUA_SOURCES_DIR,
            )
            compileDir(
                sourceDir = LIMITS_SOURCES_DIR,
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
                        includeDirs.headerFilterOnly(LUA_SOURCES_DIR, LIMITS_SOURCES_DIR)
                    }
                }
            }
        }
    }

    /*
     * Build Lua 5.4 + klua_jni.c as `libklua.{so,dylib,dll}` for each JVM host
     * via clangBuildJni. The plugin wires `-I $JAVA_HOME/include` and
     * `-I $JAVA_HOME/include/<platform>` automatically, looks up jni_md.h from
     * the local JDK on a native host and falls back to the CI-staged
     * `build/jni-include/<platform>/jni_md.h` on a cross host, and onlyIf's
     * cross-targets whose headers are unavailable. We just declare the source
     * dirs and the per-target compile flags.
     *
     * Output paths stay at `build/native/klua/<target>/dynamic/libklua.<ext>`
     * thanks to `useLibPrefix = true` baked into clangBuildJni.
     */
    val currentHost = org.jetbrains.kotlin.konan.target.HostManager.host
    val isMacHost = currentHost == org.jetbrains.kotlin.konan.target.KonanTarget.MACOS_X64 ||
        currentHost == org.jetbrains.kotlin.konan.target.KonanTarget.MACOS_ARM64
    val jvmHostTargets = buildList {
        add(org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64)
        add(org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_ARM64)
        add(org.jetbrains.kotlin.konan.target.KonanTarget.MINGW_X64)
        if (isMacHost) add(currentHost)
    }
    val jvmBuildTasks = clangBuildJni(
        name = "klua",
        targets = jvmHostTargets,
        // CI pre-populates this directory on the macOS "universal host" runner
        // so cross-compiled linux/mingw natives find a jni_md.h even when the
        // host JDK only ships the host platform's headers.
        jniHeadersOverride = layout.buildDirectory.dir("jni-include").get().asFile,
    ) {
        konanVersion.set("2.4.20")
        compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3", "-fno-rtti")
        include(LUA_SOURCES_DIR)
        include(LIMITS_SOURCES_DIR)
        compileDir(sourceDir = LUA_SOURCES_DIR)
        compileDir(sourceDir = LIMITS_SOURCES_DIR)
        compileDirJni()
    }

    /*
     * kn-clang resolves `jni_md.h` from the local JDK (`$JAVA_HOME/include/<platform>`)
     * and falls back to `jniHeadersOverride/<platform>/jni_md.h`
     * (build/jni-include/<platform>) when the host JDK does not ship that
     * platform's header. A Linux JDK has no `include/win32`, so the cross-built
     * Windows JNI library (libklua.dll, bundled into the JVM jar) needs
     * build/jni-include/win32/jni_md.h — the very file the release CI
     * downloads from OpenJDK. Stage it on demand so that `clean` + build does
     * not fail with "'jni_md.h' file not found"; the task is a no-op when the
     * header is already present (warm build/ or the CI provisioning step).
     *
     * `jni_md.h` is NOT generated by this project — it is the platform-specific
     * half of the JDK's JNI headers (jni.h + jni_md.h).
     */
    val crossJniHeaderDir = layout.buildDirectory.dir("jni-include")
    val provisionCrossJniHeaders = tasks.register("provisionCrossJniHeaders") {
        group = "build"
        description = "Stages the platform jni_md.h headers needed to cross-build the JNI libraries"
        val base = "https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base"
        val headers = mapOf(
            "linux" to "$base/unix/native/include/jni_md.h",
            "win32" to "$base/windows/native/include/jni_md.h",
        ).map { (platform, url) -> crossJniHeaderDir.get().file("$platform/jni_md.h").asFile to url }
        outputs.files(headers.map { it.first })
        doLast {
            headers.forEach { (target, url) ->
                if (!target.exists()) {
                    logger.lifecycle("[klua] staging ${target.name} for ${target.parentFile.name} from OpenJDK")
                    target.parentFile.mkdirs()
                    URI(url).toURL().openStream().use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }
    tasks.matching { it.name.startsWith("buildJniKlua") }.configureEach {
        dependsOn(provisionCrossJniHeaders)
    }

    // clangBuildJni already writes lib<name>.<ext>, so we just point each
    // copy task at it and into the per-target resource dir AGP packages.
    val jvmCopyTasks = jvmBuildTasks.associate { task ->
        val target = task.target.get()
        val libExt = when (target.family) {
            org.jetbrains.kotlin.konan.target.Family.MINGW -> "dll"
            org.jetbrains.kotlin.konan.target.Family.OSX,
            org.jetbrains.kotlin.konan.target.Family.IOS,
            org.jetbrains.kotlin.konan.target.Family.TVOS,
            org.jetbrains.kotlin.konan.target.Family.WATCHOS -> "dylib"
            else -> "so"
        }
        val srcFileName = "libklua.$libExt"
        val outDir = layout.buildDirectory.dir("processed-resources/native/${target.name}").get().asFile
        val copyTask = tasks.register("copyKluaNativeLib${target.name}", Copy::class.java) {
            from(task.dynamicFile)
            into(outDir)
            outputs.upToDateWhen { true }
        }
        target to (copyTask to srcFileName)
    }

    /*
     * Android (ART/JVM) delivery: build the same Lua 5.4 + klua_jni.c sources as a
     * dynamic library for each supported Android ABI via kn-clang (which carries
     * the NDK toolchain/sysroot, including jni.h for the ANDROID family), then drop
     * the outputs into the jniLibs tree AGP packages into the AAR.
     */
    val androidTargets = linkedMapOf(
        KonanTarget.ANDROID_ARM64 to "arm64-v8a",
        KonanTarget.ANDROID_X64 to "x86_64",
    )
    val androidCopyTasks = androidTargets.map { (target, abi) ->
        val nativeTask = clangBuildDynamic(target = target, name = "klua") {
            konanVersion.set("2.4.20")
            compileArgs("-std=gnu99", "-DLUA_COMPAT_5_3", "-fno-rtti")
            include(LUA_SOURCES_DIR)
            include(LIMITS_SOURCES_DIR)
            compileDir(sourceDir = LUA_SOURCES_DIR)
            compileDir(sourceDir = LIMITS_SOURCES_DIR)
            compileDirJni()
        }
        tasks.register("copyAndroidKlua${target.name}", Copy::class.java) {
            from(nativeTask.dynamicFile)
            rename { "libklua.so" }
            into(ANDROID_JNI_LIBS_DIR.get().dir(abi))
        }
    }
    androidNativeCopyTasks += androidCopyTasks

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
        // mingw*, macos*, ios*, watchos*, tvos*) onto the shared
        // posixMain / posixTest hierarchy. The binom-publish plugin used to
        // expose a `dependsOn(mask, to)` extension for this; we replace it
        // with a simple wildcard helper because binom-publish is no longer
        // on the classpath.
        listOf("linux", "androidNative", "mingw", "macos", "ios", "watchos", "tvos").forEach { family ->
            sourceSets.matching { it.name.startsWith(family) && it.name.endsWith("Main") }
                .configureEach { dependsOn(posixMain) }
            sourceSets.matching { it.name.startsWith(family) && it.name.endsWith("Test") }
                .configureEach { dependsOn(posixTest) }
        }

        val jvmSharedMain by creating {
            dependsOn(commonMain)
        }

        val jvmMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib:${pw.binom.Versions.KOTLIN_VERSION}")
                // luaj-jse removed; Lua is now provided by the bundled native library
                // built from the same Lua 5.4 sources used by Kotlin/Native targets.
            }
        }

        val androidMain by getting {
            dependsOn(jvmSharedMain)
        }
        tasks.named("jvmProcessResources", Copy::class.java).configure {
            dependsOn(jvmCopyTasks.values.map { it.first })
            from(layout.buildDirectory.dir("processed-resources/native"))
            include("**/*.so", "**/*.dylib", "**/*.dll")
        }

        // Stamp the project version into the JVM jar manifest so
        // NativeLoader can key its extraction cache on the real version
        // (Implementation-Version) instead of a hardcoded constant.
        tasks.named<org.gradle.jvm.tasks.Jar>("jvmJar") {
            manifest {
                attributes("Implementation-Version" to project.version.toString())
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

android {
    namespace = "pw.binom.lua"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets["main"].jniLibs.srcDir(ANDROID_JNI_LIBS_DIR)
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(androidNativeCopyTasks)
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
