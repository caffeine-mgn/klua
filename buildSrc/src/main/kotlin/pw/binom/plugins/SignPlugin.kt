package pw.binom.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.SigningExtension
import pw.binom.*

private const val GPG_KEY_ID_PROPERTY = "binom.gpg.key_id"
private const val GPG_PASSWORD_PROPERTY = "binom.gpg.password"
private const val GPG_PRIVATE_KEY_PROPERTY = "binom.gpg.private_key"

class SignPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val publishing = target.publishing
        if (publishing == null) {
            target.logger.warn("Can't sign jar files. $PUBLISH_PLUGIN_NOT_EXIST_MESSAGE")
            return
        }

        val gpgKeyId = target.propertyOrNull(GPG_KEY_ID_PROPERTY)
        val gpgPassword = target.propertyOrNull(GPG_PASSWORD_PROPERTY)
        val gpgPrivateKey = target.propertyOrNull(GPG_PRIVATE_KEY_PROPERTY)?.replace("|", "\n")

        if (gpgKeyId == null || gpgPassword == null || gpgPrivateKey != null) {
            val sb = StringBuilder()
            sb.appendLine("gpg configuration missing. Jar will be publish without sign. Reasons:")
            if (gpgKeyId == null) {
                sb.appendLine("  Property $GPG_KEY_ID_PROPERTY not found")
            }
            if (gpgPassword == null) {
                sb.appendLine("  Property $GPG_PASSWORD_PROPERTY not found")
            }
            if (gpgPrivateKey == null) {
                sb.appendLine("  Property $GPG_PRIVATE_KEY_PROPERTY not found")
            }
            target.logger.warn(sb.toString())
        }

        target.applyPluginIfNotApplyed("signing")
        target.extensions.configure(SigningExtension::class.java) {
            it.useInMemoryPgpKeys(gpgKeyId, gpgPrivateKey, gpgPassword)
            it.sign(publishing.publications)
            it.setRequired(target.tasks.filterIsInstance<PublishToMavenRepository>())
        }
    }
}
