buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
        maven(url = "https://repo.binom.pw")
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    kotlin("jvm") version "2.4.20"
}

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://repo.binom.pw")
    maven(url = "https://plugins.gradle.org/m2/")
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    api("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    api("org.jetbrains.dokka:dokka-gradle-plugin:1.9.20")
    api("pw.binom:binom-publish:0.1.23")
    api("io.vertx:vertx-web:4.2.1")
}