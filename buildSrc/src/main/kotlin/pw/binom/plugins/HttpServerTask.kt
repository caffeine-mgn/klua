package pw.binom.plugins

import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File

abstract class HttpServerTask : DefaultTask() {
    private val vertx = Vertx.vertx()
    private var server: HttpServer? = null

    @get:InputDirectory
    abstract val root: RegularFileProperty

    @get:Input
    abstract val port: Property<Int>

    fun port(port: Int) {
        this.port.set(port)
    }

    fun root(file: File) {
        root.set(file)
    }

    @TaskAction
    open fun start() {
        val server = vertx.createHttpServer()
        this.server = server
        server.requestHandler { request ->
            if (request.method().name() == "POST") {
                request.body {
                    println("js: ${String(it.result().bytes)}")
                    request.response().also {
                        it.headers()["Access-Control-Allow-Origin"] = "*"
                        it.statusCode = 204
                        it.end()
                    }
                }
                return@requestHandler
            }
            val rootFile = root.get().asFile
            val targetFile = rootFile.resolve(request.path().removePrefix("/"))
            if (!targetFile.isFile) {
                logger.warn("File $targetFile not found")
                request.response().also {
                    it.statusCode = 404
                    it.end()
                }
                return@requestHandler
            }

            request.response().also {
                logger.warn("File $targetFile found")
                it.statusCode = 200
                val mimeType = when (targetFile.extension.toLowerCase()) {
                    "wasm" -> "application/wasm"
                    "js" -> "application/javascript"
                    "htm", "html" -> "text/html"
                    else -> TODO()
                }
                it.headers()["Content-Type"] = mimeType
                it.headers()["Access-Control-Allow-Origin"] = "*"
                it.sendFile(targetFile.absolutePath)
            }
        }
        logger.info("Http server started on port ${port.get()}. Root directory: ${root.get().asFile.absolutePath}")
        server.listen(port.get())
    }

    open fun stop() {
        server?.also {
            it.close()
            logger.warn("Http server stoped")
        }
        server = null
    }

    open fun runDuringTask(task: Task) {
        task.dependsOn(this)
    }

    open fun runDuringTask(tt: TaskProvider<out Task>) {
        tt.configure {
            runDuringTask(it)
        }
    }
}
