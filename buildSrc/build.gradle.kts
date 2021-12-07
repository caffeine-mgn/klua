buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
        maven(url = "https://repo.binom.pw")
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.6.0")
    }
}

plugins {
    kotlin("jvm") version "1.6.0"
}

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://repo.binom.pw")
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:1.6.0")
    api("org.jetbrains.kotlin:kotlin-gradle-plugin:1.6.0")
    api("org.jetbrains.dokka:dokka-gradle-plugin:1.5.0")
    api("pw.binom:kn-clang:0.1")
    api("io.vertx:vertx-web:4.2.1")
}