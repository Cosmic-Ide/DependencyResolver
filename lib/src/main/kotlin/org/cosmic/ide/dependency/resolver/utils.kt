/*
 *
 *  This file is part of Cosmic IDE.
 *  Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *  Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *  You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.cosmic.ide.dependency.resolver

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.cosmic.ide.dependency.resolver.api.Artifact
import org.cosmic.ide.dependency.resolver.api.EventReciever
import org.cosmic.ide.dependency.resolver.api.Repository
import org.cosmic.ide.dependency.resolver.repository.GoogleMaven
import org.cosmic.ide.dependency.resolver.repository.Jitpack
import org.cosmic.ide.dependency.resolver.repository.MavenCentral
import org.cosmic.ide.dependency.resolver.repository.SonatypeSnapshots
import org.w3c.dom.Element
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import javax.xml.parsers.DocumentBuilderFactory

val repositories = ConcurrentLinkedQueue<Repository>().apply {
    addAll(listOf(MavenCentral(), Jitpack(), GoogleMaven(), SonatypeSnapshots()))
}
var eventReciever = EventReciever()

fun getArtifact(groupId: String, artifactId: String, version: String): Artifact? {
    val artifact = initHost(Artifact(groupId, artifactId, version)) ?: return null
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(artifact.getPOM()!!)

    val packaging = doc.getElementsByTagName("packaging").item(0)
    if (packaging != null) artifact.extension = packaging.textContent
    return artifact
}

/*
 * Finds the host repository of the artifact and initialises it.
 * Returns null if no repository hosts this artifact
 */
fun initHost(artifact: Artifact): Artifact? {
    for (repository in repositories) {
        if (repository.checkExists(artifact)) {
            artifact.repository = repository
            eventReciever.onArtifactFound(artifact)
            return artifact
        }
    }
    eventReciever.onArtifactNotFound(artifact)
    return null
}

/*
 * Resolves a POM file from InputStream and returns the list of artifacts it depends on.
 */
suspend fun InputStream.resolvePOM(resolved: ConcurrentLinkedQueue<Artifact>): ConcurrentLinkedQueue<Artifact> = coroutineScope {
    val artifacts = ConcurrentLinkedQueue<Artifact>()
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(this@resolvePOM)

    val elem = doc.getElementsByTagName("dependencies")
    if (elem.length == 0) {
        eventReciever.onDependenciesNotFound(Artifact(doc.getElementsByTagName("groupId").item(0).textContent, doc.getElementsByTagName("artifactId").item(0).textContent, doc.getElementsByTagName("version").item(0).textContent))
        return@coroutineScope artifacts
    }

    val dependencyElements = elem.item(elem.length - 1) as Element
    val dependencies = dependencyElements.getElementsByTagName("dependency")

    val deferred = (0 until dependencies.length).map { i ->
        async {
            val dependencyElement = dependencies.item(i) as Element
            val scopeItem = dependencyElement.getElementsByTagName("scope").item(0)
            if (scopeItem != null) {
                val scope = scopeItem.textContent
                if (scope.isNotEmpty() && (scope == "test" || scope == "provided")) {
                    eventReciever.onInvalidScope(Artifact(dependencyElement.getElementsByTagName("groupId").item(0)?.textContent ?: "", dependencyElement.getElementsByTagName("artifactId").item(0)?.textContent ?: "", dependencyElement.getElementsByTagName("version").item(0)?.textContent ?: ""), scope)
                    return@async
                }
            }
            val groupId = dependencyElement.getElementsByTagName("groupId").item(0).textContent
            val artifactId = dependencyElement.getElementsByTagName("artifactId").item(0).textContent
            if (artifactId.endsWith("bom")) {
                return@async
            }
            val item = dependencyElement.getElementsByTagName("version").item(0)?.textContent ?: ""

            if (resolved.any { it.groupId == groupId && it.artifactId == artifactId && it.version >= item }) {
                eventReciever.onSkippingResolution(Artifact(groupId, artifactId, item))
                return@async
            }

            val artifact = Artifact(groupId, artifactId)
            initHost(artifact)
            if (artifact.repository == null) {
                return@async
            }

            val metadata = artifact.getMavenMetadata()
            var version = item
            if (version.startsWith("[")) {
                val versions = version.substring(1, version.length - 1).split(",")
                versions.forEach { v ->
                    if (metadata.contains(v)) {
                        version = v
                        return@forEach
                    }
                }
            }
            if (version == "+") {
                version = ""
            }
            if (version.startsWith("\${")) {
                val tagName = version.substring(2, version.length - 1)
                val tag = doc.getElementsByTagName(tagName).item(0)
                if (tag == null) {
                    eventReciever.onVersionNotFound(artifact)
                    return@async
                }
                version = tag.textContent
            }
            artifact.version = version
            artifacts.add(artifact)
        }
    }
    deferred.awaitAll()
    artifacts
}



suspend fun <T> Iterable<T>.parallelForEach(action: suspend (T) -> Unit) = coroutineScope {
    map { element ->
        async { action(element) }
    }.awaitAll()
}
