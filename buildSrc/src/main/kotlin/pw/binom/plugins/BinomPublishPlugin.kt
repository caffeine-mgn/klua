package pw.binom.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.net.URI
import java.util.logging.Logger

const val BINOM_REPO_URL = "binom.repo.url"
const val BINOM_REPO_USER = "binom.repo.user"
const val BINOM_REPO_PASSWORD = "binom.repo.password"

class BinomPublishPlugin : Plugin<Project> {
    private val logger = Logger.getLogger(this::class.java.name)
    override fun apply(target: Project) {
        if (!target.hasProperty(BINOM_REPO_URL)) {
            logger.warning("Property [$BINOM_REPO_URL] not found publication plugin will not apply")
            return
        }
        if (!target.hasProperty(BINOM_REPO_USER)) {
            logger.warning("Property [$BINOM_REPO_USER] not found publication plugin will not apply")
            return
        }
        if (!target.hasProperty(BINOM_REPO_PASSWORD)) {
            logger.warning("Property [$BINOM_REPO_PASSWORD] not found publication plugin will not apply")
            return
        }

        target.apply {
            it.plugin("maven-publish")
        }

        val publishing = target.extensions.findByName("publishing") as PublishingExtension
        publishing.repositories {
            it.maven {
                it.name = "BinomRepository"
                it.url = URI(target.property(BINOM_REPO_URL) as String)
                it.credentials {
                    it.username = target.property(BINOM_REPO_USER) as String
                    it.password = target.property(BINOM_REPO_PASSWORD) as String
                }
            }
        }
        publishing.publications.withType(MavenPublication::class.java) {
            it.pom {
                it.scm {
                    it.connection.set("git@github.com:caffeine-mgn/kn-clang-compiler-plugin.git")
                    it.url.set("https://github.com/caffeine-mgn/kn-clang-compiler-plugin")
                }
                it.developers {
                    it.developer {
                        it.id.set("subochev")
                        it.name.set("Anton Subochev")
                        it.email.set("caffeine.mgn@gmail.com")
                    }
                }
                it.licenses {
                    it.license {
                        it.name.set("The Apache License, Version 2.0")
                        it.url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}