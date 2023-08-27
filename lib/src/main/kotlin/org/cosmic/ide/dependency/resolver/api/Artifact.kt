/*
 *
 *  * This file is part of Cosmic IDE.
 *  * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *  * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *  * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 *
 *
 */

package org.cosmic.ide.dependency.resolver.api

import org.cosmic.ide.dependency.resolver.logger
import org.cosmic.ide.dependency.resolver.parallelForEach
import org.cosmic.ide.dependency.resolver.resolvePOM
import java.io.File
import java.io.InputStream
import java.net.SocketException
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import javax.xml.parsers.DocumentBuilderFactory

data class Artifact(
    val groupId: String,
    val artifactId: String,
    var version: String = "",
    var repository: Repository? = null,
    var extension: String = "jar"
) {
    fun downloadTo(output: File) {
        if (repository == null) {
            throw IllegalStateException("Repository is not declared.")
        }
        output.createNewFile()
        val dependencyUrl =
            "${ repository!!.getURL() }/${ groupId.replace(".", "/") }/$artifactId/$version/$artifactId-$version" + "." + extension
        logger.info("Downloading $dependencyUrl")
        try {
            val stream = URL(dependencyUrl).openConnection().apply { connectTimeout = 2000; readTimeout = 5000 }.inputStream
            output.outputStream().use { stream.copyTo(it) }
        } catch (e: SocketException) {
            downloadTo(output)
        }
    }

    suspend fun downloadArtifact(output: File) {
        output.mkdirs()
        val artifacts = resolve()
        artifacts.add(this)

        val latestDeps =
            ConcurrentLinkedQueue(artifacts.groupBy { it.groupId to it.artifactId }.values.map { artifact -> artifact.maxBy { it.version } })

        latestDeps.parallelForEach { art ->
            val pom = art.getPOM()
            if (pom != null) {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(pom)
                val packaging = doc.getElementsByTagName("packaging").item(0)
                if (packaging != null) art.extension = packaging.textContent
            }
            if (art.version.isNotEmpty() && art.repository != null) {
                art.downloadTo(File(output, "${ art.artifactId }-${ art.version }" + "." + extension))
            }
        }
    }

    suspend fun resolve(resolved: ConcurrentLinkedQueue<Artifact> = ConcurrentLinkedQueue()): ConcurrentLinkedQueue<Artifact> {
        val pom = getPOM() ?: return ConcurrentLinkedQueue()
        val deps = ConcurrentLinkedQueue(pom.resolvePOM(resolved))
        val artifacts = ConcurrentLinkedQueue<Artifact>()
        deps.parallelForEach { dep ->
            logger.fine("Resolving $dep from $this")
            if (dep.version.isBlank()) {
                logger.info("Fetching latest version of ${dep.artifactId}")
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(dep.getMavenMetadata().byteInputStream())
                val v = doc.getElementsByTagName("release").item(0)
                if (v != null) {
                    dep.version = v.textContent
                    logger.info("Latest version of ${dep.groupId}:${dep.artifactId} is ${v.textContent}")
                }
            }
            artifacts.add(dep)
            if (resolved.any { it.groupId == dep.groupId && it.artifactId == dep.artifactId && it.version >= dep.version }) {
                return@parallelForEach
            }
            resolved.add(dep)
            val depArtifacts = dep.resolve(resolved)
            logger.info("Resolved $dep")
            resolved.addAll(depArtifacts)
            artifacts.addAll(depArtifacts)
        }
        return artifacts
    }

    fun getMavenMetadata(): String {
        if (repository == null) {
            return ""
        }
        val dependencyUrl =
            "${repository?.getURL()}/${groupId.replace(".", "/")}/${artifactId}/maven-metadata.xml"
        return URL(dependencyUrl).openConnection().apply { connectTimeout = 2000; readTimeout = 5000 }.inputStream.bufferedReader().readText()
    }


    fun getPOM(): InputStream? {
        val pomUrl =
            "${ repository!!.getURL() }/${ groupId.replace(".", "/") }/$artifactId/$version/$artifactId-$version.pom"
        if (version.isNotEmpty()) {
            return try {
                URL(pomUrl).openConnection()
                    .apply { connectTimeout = 5000; readTimeout = 2000 }.inputStream
            } catch (e: SocketException) {
                getPOM()
            }
        }
        return null
    }

    override fun toString(): String {
        return "$groupId:$artifactId:$version"
    }
}
