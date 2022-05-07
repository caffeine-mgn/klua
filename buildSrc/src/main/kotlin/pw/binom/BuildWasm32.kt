package pw.binom

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.Incremental
import org.jetbrains.kotlin.konan.target.HostManager
import pw.binom.kotlin.clang.*
import java.io.File

abstract class BuildBinaryWasm32 : DefaultTask() {
    @get:OutputFile
    abstract val output: Property<File>

    @get:OutputFile
    val output2: File
        get() {
            val out = output.get()
            return out.parentFile.resolve("${out.nameWithoutExtension}.wasm")
        }

    @get:Incremental
    @get:InputFiles
//    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cppFiles: ConfigurableFileCollection

    @get:Input
    abstract val customArgs: ListProperty<String>

    @TaskAction
    fun execute() {
//        return
        val d = cppFiles.asFileTree.files.maxOfOrNull { it.lastModified() } ?: 0L
        val o = output.get().lastModified()
        if (d < o) {
            return
        }
//        if (!output.get().asFile.exists()) {
//            println("File ${output.get().asFile} not exist. Main 10 minutes")
//            Thread.sleep(1000 * 60 * 60 * 10)
//        }
        val args = ArrayList<String>()
        val emmcBin = if (HostManager.hostIsMingw) {
            "emcc.bat"
        } else {
            "emcc"
        }
        args += emmcBin
//        wrapBatchCmd(args)
        cppFiles.forEach {
            args += it.absolutePath
        }
        args += "-o"
        args += output.get().absolutePath
        val env = HashMap(System.getenv())
        this.customArgs.get().forEach {
            args += it
        }
        val exitCode = startProcessAndWait(
            args = args,
            workDirectory = project.buildDir,
            envs = env
        )
        if (exitCode != 0) {
            throw RuntimeException("Can't build ${output.get()}")
        }
    }
}
