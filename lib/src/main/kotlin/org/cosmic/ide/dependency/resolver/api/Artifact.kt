/*
 *
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.cosmic.ide.dependency.resolver.api

import okhttp3.Request
import org.cosmic.ide.dependency.resolver.eventReciever
import org.cosmic.ide.dependency.resolver.okHttpClient
import org.cosmic.ide.dependency.resolver.resolveDependencies
import org.cosmic.ide.dependency.resolver.xmlDeserializer
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

data class Artifact(
    val groupId: String,
    val artifactId: String,
    var version: String = "",
    var repository: Repository? = null,
    var extension: String = "jar"
) {
    var mavenMetadata: MavenMetadata? = null

    // Direct dependencies of the artifact.
    var dependencies: List<Artifact>? = null

    // The Project Object Model of the artifact.
    var pom: ProjectObjectModel? = null

    /**
     * Downloads the artifact to the specified directory.
     *
     * @param output The directory to download the artifact to.
     */
    suspend fun downloadArtifact(output: File) {
        output.mkdirs()
        if (dependencies == null) {
            resolveDependencyTree()
        }

        val artifacts = getAllDependencies()

        dependencies = dependencies?.distinctBy { it.groupId + it.artifactId }

        artifacts.forEach { artifact ->
            artifact.downloadTo(
                File(
                    output, "${artifact.artifactId}-${artifact.version}.${artifact.extension}"
                )
            )
        }
    }

    /**
     * Gets all the dependencies of the artifact.
     *
     * @return A list of all the dependencies of the artifact.
     */
    suspend fun getAllDependencies(): List<Artifact> {
        if (dependencies == null) {
            resolveDependencyTree()
        }
        val deps = mutableListOf<Artifact>()
        dependencies!!.forEach { dep ->
            deps.add(dep)
            deps.addAll(dep.getAllDependencies())
        }
        return deps
    }

    /**
     * Prints the dependency tree of the artifact.
     *
     * @param depth The depth of the dependency tree.
     */
    suspend fun showDependencyTree(depth: Int = 0) {
        if (dependencies == null) {
            resolveDependencyTree()
        }
        println("    ".repeat(depth) + this)
        dependencies!!.forEach { dep ->
            dep.showDependencyTree(depth + 1)
        }
    }

    /**
     * Resolves the artifact and its dependencies.
     *
     * @param resolved The list of resolved artifacts.
     * @return The list of resolved artifacts.
     */
    suspend fun resolve(resolved: ConcurrentHashMap<Artifact, ConcurrentLinkedDeque<Artifact>> = ConcurrentHashMap<Artifact, ConcurrentLinkedDeque<Artifact>>(), managedDependencies: ConcurrentLinkedDeque<Artifact> = ConcurrentLinkedDeque()): ConcurrentHashMap<Artifact, ConcurrentLinkedDeque<Artifact>> {
        if (repository == null) {
            throw IllegalStateException("Repository is not declared.")
        }

        if (dependencies != null) {
            eventReciever.onSkippingResolution(this)
            return resolved
        }

        val deps = getPOM()?.resolveDependencies(resolved, managedDependencies)
        if (deps != null) {
            resolved[this] = deps
            dependencies = deps.toList()
        } else {
            dependencies = emptyList()
            eventReciever.onDependenciesNotFound(this)
        }

        return resolved
    }

    suspend fun resolveDependencyTree(resolved: ConcurrentHashMap<Artifact, ConcurrentLinkedDeque<Artifact>> = ConcurrentHashMap<Artifact, ConcurrentLinkedDeque<Artifact>>(), managedDependencies: ConcurrentLinkedDeque<Artifact> = ConcurrentLinkedDeque()): List<Artifact> {
        if (dependencies == null) {
            resolve(resolved, managedDependencies)
        }
        val deps = mutableListOf<Artifact>()
        dependencies!!.forEach { dep ->
            deps.add(dep)
            deps.addAll(dep.resolveDependencyTree(resolved, managedDependencies))
        }
        return deps
    }

    /**
     * Downloads the artifact to the specified file.
     *
     * @param output The file to download the artifact to.
     */
    fun downloadTo(output: File) {
        if (repository == null) {
            throw IllegalStateException("Repository is not declared.")
        }
        output.createNewFile()
        val dependencyUrl = "${repository!!.getURL()}/${
            groupId.replace(
                ".", "/"
            )
        }/$artifactId/$version/$artifactId-$version.$extension"
        eventReciever.onDownloadStart(this)
        val request = Request.Builder().url(dependencyUrl).build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                response.body.byteStream().use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
            }
            eventReciever.onDownloadEnd(this)
        } catch (e: Exception) {
            eventReciever.onDownloadError(this, e)
        }
    }

    /**
     * Gets the Project Object Model of the artifact.
     *
     * @return The Project Object Model of the artifact.
     */
    fun getPOM(): ProjectObjectModel? {
        if (pom != null) {
            return pom
        }
        if (repository == null) {
            throw IllegalStateException("Repository is not declared.")
        }
        if (version.isEmpty()) {
            throw IllegalStateException("Version is not declared.")
        }
        val pomUrl = "${repository?.getURL()}/${
            groupId.replace(
                ".", "/"
            )
        }/$artifactId/$version/$artifactId-$version.pom"

        val request = Request.Builder().url(pomUrl).build()
        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            pom = xmlDeserializer.readValue(
                response.body.byteStream(),
                ProjectObjectModel::class.java
            )
        } catch (_: SocketException) {
            eventReciever.onVersionNotFound(this)
        } catch (e: Exception) {
            e.printStackTrace()
            eventReciever.onInvalidPOM(this)
        }
        return pom
    }

    override fun toString(): String {
        return "$groupId:$artifactId:$version"
    }

    override fun hashCode(): Int {
        return "$groupId:$artifactId".hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Artifact

        if (groupId != other.groupId) return false
        if (artifactId != other.artifactId) return false
        if (version != other.version) return false
        if (repository != other.repository) return false

        return true
    }
}
