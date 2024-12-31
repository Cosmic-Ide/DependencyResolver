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
import org.cosmic.ide.dependency.resolver.getLatestRangeVersion
import org.cosmic.ide.dependency.resolver.okHttpClient
import org.cosmic.ide.dependency.resolver.parallelForEach
import org.cosmic.ide.dependency.resolver.resolvePOM
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.xml.parsers.DocumentBuilderFactory

data class Artifact(
    val groupId: String,
    val artifactId: String,
    var version: String = "",
    var repository: Repository? = null,
    var extension: String = "jar"
) {
    var mavenMetadata: String = ""

    var dependencies: List<Artifact>? = null

    suspend fun downloadArtifact(output: File) {
        output.mkdirs()
        if (dependencies == null) {
            resolve()
        }

        val artifacts = getAllDependencies()
        artifacts.groupBy { it.groupId to it.artifactId }.values.mapNotNull { artifacts ->
            artifacts.maxByOrNull { it.version }
        }.forEach { artifact ->
            artifact.downloadTo(
                File(
                    output, "${artifact.artifactId}-${artifact.version}.${artifact.extension}"
                )
            )
        }
    }

    suspend fun getAllDependencies(): List<Artifact> {
        if (dependencies == null) {
            resolve()
        }
        val deps = mutableListOf<Artifact>()
        dependencies!!.forEach { dep ->
            deps.add(dep)
            deps.addAll(dep.getAllDependencies())
        }
        return deps
    }

    suspend fun showDependencyTree(depth: Int = 0) {
        if (dependencies == null) {
            resolve()
        }
        println("    ".repeat(depth) + this)
        dependencies!!.forEach { dep ->
            dep.showDependencyTree(depth + 1)
        }
    }

    suspend fun resolve(resolved: ConcurrentLinkedQueue<Artifact> = ConcurrentLinkedQueue<Artifact>()): ConcurrentLinkedQueue<Artifact> {
        if (listOf(resolved.find { it.groupId == groupId && it.artifactId == artifactId }?.version ?: "", version).maxOrNull() != version) {
            return resolved
        }

        val pom = getPOM() ?: return ConcurrentLinkedQueue()

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(pom)
        val packaging = doc.getElementsByTagName("packaging").item(0)
        if (packaging != null) extension = packaging.textContent

        val deps = doc.resolvePOM(resolved)
        val artifacts = ConcurrentLinkedQueue<Artifact>()
        deps.parallelForEach { dep ->
            eventReciever.onResolving(this, dep)

            val saved =
                resolved.find { it.groupId == dep.groupId && it.artifactId == dep.artifactId }

            if (saved != null) {
                val newer = maxOf(
                    saved.version, if (dep.version.startsWith("[")) getLatestRangeVersion(
                        saved, dep.version
                    ) else dep.version
                )
                if (newer != saved.version) {
                    saved.version = newer
                    saved.resolve(resolved)
                } else {
                    eventReciever.onSkippingResolution(dep)
                }
                return@parallelForEach
            }
            artifacts.add(dep)
            resolved.add(dep)
            dep.resolve(resolved)
            eventReciever.onResolutionComplete(dep)
        }
        dependencies = artifacts.toList()
        return resolved
    }

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

    fun getPOM(): InputStream? {
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
            return response.body.byteStream()
        } catch (_: SocketException) {
            eventReciever.onVersionNotFound(this)
        } catch (e: Exception) {
            e.printStackTrace()
            eventReciever.onInvalidPOM(this)
        }
        return null
    }

    override fun toString(): String {
        return "$groupId:$artifactId:$version"
    }
}
