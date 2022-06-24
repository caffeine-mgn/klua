package pw.binom

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.TaskContainer
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinNativeCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import java.io.ByteArrayOutputStream

fun Project.propertyOrNull(property: String) =
    if (hasProperty(property)) property(property) as String else null

fun Project.stringProperty(property: String) =
    propertyOrNull(property) ?: throw GradleException("Property \"$property\" not set")

@get:JvmName("getPublishingOrNull")
val Project.publishing
    get() = (extensions.findByName("publishing") as PublishingExtension?)

val Project.isSnapshot
    get() = (version as String).endsWith("-SNAPSHOT")

fun Project.applyPluginIfNotApplyed(name:String) {
    apply {
        it.plugin(name)
    }
}

fun Project.getGitBranch(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        it.commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
        it.standardOutput = stdout
    }
    return stdout.toString().trim()
}

fun Project.getGitHash(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        it.commandLine("git", "rev-parse", "--short", "HEAD")
        it.standardOutput = stdout
    }
    return stdout.toString().trim()
}
