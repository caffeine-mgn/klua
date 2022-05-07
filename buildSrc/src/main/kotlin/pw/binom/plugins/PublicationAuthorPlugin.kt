package pw.binom.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication
import pw.binom.getPublishing
import pw.binom.publishing

class PublicationAuthorPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val authorExtension = target.extensions.create("pomInfo", PublicationPomInfoExtension::class.java)

        val publishing = target.publishing
        if (publishing == null) {
            target.logger.warn("Can't apply pom info. $PUBLISH_PLUGIN_NOT_EXIST_MESSAGE")
            return
        }

        publishing.publications.withType(MavenPublication::class.java) {
            it.pom {
                val scm = authorExtension.scmProperty.orNull
                if (scm != null) {
                    it.scm {
                        it.connection.set(scm.gitUrl)
                        it.url.set(scm.httpUrl)
                    }
                }
                val developers = authorExtension.authorProperties.orNull ?: emptyList()
                if (developers.isNotEmpty()) {
                    it.developers {
                        developers.forEach { developer ->
                            it.developer {
                                it.id.set(developer.id)
                                if (developer.name != null) {
                                    it.name.set(developer.name)
                                }
                                if (developer.email != null) {
                                    it.email.set(developer.email)
                                }
                            }
                        }
                    }
                }
                val license = authorExtension.licenseProperty.orNull
                if (license != null) {
                    it.licenses {
                        it.license {
                            if (license.name != null) {
                                it.name.set(license.name)
                            }
                            if (license.url != null) {
                                it.url.set(license.url)
                            }
                        }
                    }
                }
            }
        }
    }
}
