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
import org.cosmic.ide.dependency.resolver.okHttpClient
import org.cosmic.ide.dependency.resolver.eventReciever
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

    suspend fun downloadArtifact(output: File) {
        output.mkdirs()
        val artifacts = resolve()
        artifacts.add(this)

        val latestDeps =
            ConcurrentLinkedQueue(artifacts.groupBy { it.groupId to it.artifactId }.values.mapNotNull { artifact -> artifact.maxByOrNull { it.version } })

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
                art.downloadTo(File(output, "${art.artifactId}-${art.version}.$extension"))
            }
        }
    }

    suspend fun resolve(resolved: ConcurrentLinkedQueue<Artifact> = ConcurrentLinkedQueue<Artifact>()): ConcurrentLinkedQueue<Artifact> {
        val pom = getPOM() ?: return ConcurrentLinkedQueue()
        val deps = ConcurrentLinkedQueue(pom.resolvePOM(resolved))
        val artifacts = ConcurrentLinkedQueue<Artifact>()
        deps.parallelForEach { dep ->
            eventReciever.onResolving(this, dep)

            val saved = resolved.find { it.groupId == dep.groupId && it.artifactId == dep.artifactId }

            if (saved != null) {
                val max = listOf(saved.version, dep.version).maxOrNull()
                if (saved.version != max) {
                    println("Updating max of ${saved.version} to $max")
                    saved.version = max ?: ""
                }
                return@parallelForEach
            }
            artifacts.add(dep)
            resolved.add(dep)
            val depArtifacts = dep.resolve(resolved)
            eventReciever.onResolutionComplete(dep)
            resolved.addAll(depArtifacts)
            artifacts.addAll(depArtifacts)
        }
        return artifacts
    }
    
    fun downloadTo(output: File) {
        if (repository == null) {
            throw IllegalStateException("Repository is not declared.")
        }
        output.createNewFile()
        val dependencyUrl =
            "${repository!!.getURL()}/${groupId.replace(".", "/")}/$artifactId/$version/$artifactId-$version.$extension"
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
        val pomUrl =
            "${repository?.getURL()}/${groupId.replace(".", "/")}/$artifactId/$version/$artifactId-$version.pom"

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
