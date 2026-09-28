buildscript {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven(url = "https://repo.binom.pw")
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("com.android.tools.build:gradle:8.10.1")
    }
}

plugins {
    kotlin("jvm") version "2.4.20"
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
    maven(url = "https://repo.binom.pw")
    maven(url = "https://plugins.gradle.org/m2/")
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    api("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    api("com.android.tools.build:gradle:8.10.1")
}