package pw.binom.plugins

import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Optional

open class PublicationPomInfoExtension(objects: ObjectFactory) {
    val licenseProperty = objects.property(License::class.java)

    @Optional
    val authorProperties = objects.listProperty(Author::class.java)

    @Optional
    val scmProperty = objects.property(Scm::class.java)

    class Scm(
        val gitUrl: String?,
        val httpUrl: String?,
    )

    fun scm(gitUrl: String?, httpUrl: String?) {
        scmProperty.set(
            Scm(
                gitUrl = gitUrl,
                httpUrl = httpUrl,
            )
        )
    }

    fun gitScm(urlToProject: String) {
        scm(
            gitUrl = "$urlToProject.git",
            httpUrl = urlToProject,
        )
    }

    fun useApache2License() {
        licenseProperty.set(License.Apache2)
    }

    fun author(id: String, name: String? = null, email: String? = null) {
        authorProperties.add(
            Author(
                id = id,
                name = name,
                email = email,
            )
        )
    }

    class Author(
        val id: String,
        val name: String?,
        val email: String?,
    )

    interface License {
        val name: String?
        val url: String?

        object Apache2 : License {
            override val name: String?
                get() = "The Apache License, Version 2.0"
            override val url: String
                get() = "http://www.apache.org/licenses/LICENSE-2.0.txt"
        }
    }
}
