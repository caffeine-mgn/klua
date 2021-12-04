package pw.binom

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import pw.binom.kotlin.clang.*
import org.jetbrains.kotlin.konan.target.HostManager

abstract class BuildBinaryWasm32 : DefaultTask() {
    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cppFiles: ConfigurableFileCollection

    @get:Input
    abstract val customArgs: ListProperty<String>

    @TaskAction
    fun execute() {
        val args = ArrayList<String>()
        val emmcBin = if (HostManager.hostIsMingw) {
            "emcc.bat"
        } else {
            "emcc"
        }
        args += emmcBin
        wrapBatchCmd(args)
        cppFiles.forEach {
            args += it.absolutePath
        }
        args += "-o"
        args += output.get().asFile.absolutePath
        val env = HashMap(System.getenv())
        this.customArgs.get().forEach {
            args += it
        }
        println("Args: $args")
        val exitCode = startProcessAndWait(
            args = args,
            workDirectory = project.buildDir,
            envs = env
        )
        if (exitCode != 0) {
            throw RuntimeException("Can't build ${output.get().asFile}")
        }
    }
}