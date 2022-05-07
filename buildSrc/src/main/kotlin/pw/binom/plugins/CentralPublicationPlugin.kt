package pw.binom.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import pw.binom.*
import pw.binom.getPublishing
import java.net.URI
import java.util.logging.Logger

class CentralPublicationPlugin : Plugin<Project> {
    private val logger = Logger.getLogger(this::class.java.name)
    override fun apply(target: Project) {
        val centralUserName = target.stringProperty("binom.central.username")
        val centralPassword = target.stringProperty("binom.central.password")
        val publishing = target.getPublishing()
        publishing.repositories {
            it.maven {
                it.name = "Central"
                val url = if (target.isSnapshot)
                    "https://s01.oss.sonatype.org/content/repositories/snapshots"
                else
                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2"
                it.url = URI(url)
                it.credentials {
                    it.username = centralUserName
                    it.password = centralPassword
                }
            }
        }
    }
}
