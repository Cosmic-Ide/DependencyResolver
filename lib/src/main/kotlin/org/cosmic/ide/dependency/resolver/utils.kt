/*
 *
 *  * This file is part of Cosmic IDE.
 *  * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *  * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *  * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 *
 *
 */

package org.cosmic.ide.dependency.resolver

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.cosmic.ide.dependency.resolver.api.Artifact
import org.cosmic.ide.dependency.resolver.api.Repository
import org.cosmic.ide.dependency.resolver.repository.GoogleMaven
import org.cosmic.ide.dependency.resolver.repository.Jitpack
import org.cosmic.ide.dependency.resolver.repository.MavenCentral
import org.cosmic.ide.dependency.resolver.repository.SonatypeSnapshots
import org.w3c.dom.Element
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory

val repositories = ConcurrentLinkedQueue<Repository>().apply {
    addAll(listOf(MavenCentral(), Jitpack(), GoogleMaven(), SonatypeSnapshots()))
}
val logger: Logger = Logger.getLogger("DependencyResolver")

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
            logger.info("Found ${artifact.groupId + ":" + artifact.artifactId} in ${repository.getName()}")
            artifact.repository = repository
            return artifact
        }
    }
    logger.info("No repository contains ${artifact.artifactId}:${artifact.version}")
    return null
}

/*
 * Resolves a POM file from InputStream and returns the list of artifacts it depends on.
 */
suspend fun InputStream.resolvePOM(resolved: ConcurrentLinkedQueue<Artifact>): ConcurrentLinkedQueue<Artifact> {
    val artifacts = ConcurrentLinkedQueue<Artifact>()
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(this)

    val elem = doc.getElementsByTagName("dependencies")
    if (elem.length == 0) {
        logger.fine("No dependencies found")
        return artifacts
    }
    val dependencies = elem.item(elem.length - 1) as Element

    val dependencyElements = dependencies.getElementsByTagName("dependency")

    val items = mutableListOf<Int>()
    for (i in 0 until dependencyElements.length) {
        items.add(i)
    }
    items.parallelForEach { i ->
        val dependencyElement = dependencyElements.item(i) as Element
        val scopeItem = dependencyElement.getElementsByTagName("scope").item(0)
        if (scopeItem != null) {
            val scope = scopeItem.textContent
            // if scope is test/provided, there is no need to download them
            if (scope.isNotEmpty() && (scope == "test" || scope == "provided")) {
                return@parallelForEach
            }
        }
        val groupId = dependencyElement.getElementsByTagName("groupId").item(0).textContent
        val artifactId = dependencyElement.getElementsByTagName("artifactId").item(0).textContent
        if (artifactId.endsWith("bom")) {
            // TODO: handle versions from BOMs
            return@parallelForEach
        }
        val item = dependencyElement.getElementsByTagName("version").item(0)

        if (resolved.any { it.groupId == groupId && it.artifactId == artifactId && it.version >= item.textContent }) {
            logger.info("Skipping $groupId:$artifactId as it is already resolved.")
            return@parallelForEach
        }
        val artifact = Artifact(groupId, artifactId)
        initHost(artifact)
        if (artifact.repository == null) {
            return@parallelForEach
        }

        val metadata = artifact.getMavenMetadata()
        if (item != null) {
            var version = item.textContent
            // Some libraries define an array of compatible dependency versions.
            if (version.startsWith("[")) {
                // remove square brackets so that we have something like `1.0.0,1.0.1` left
                logger.info("Found array of versions for ${artifact.groupId}:${artifact.artifactId}")
                logger.info(version)
                val versionArray = version.substring(1, version.length - 1)
                val versions = versionArray.split(",")
                // sometimes, some of the defined versions don't exist, so we need to check all of them
                versions.forEach { v ->
                    if (metadata.contains(v)) {
                        version = v
                    }
                }
            }
            if (version == "+") {
                // The latest version will be fetched later in resolve()
                version = ""
            }
            if (version.startsWith("\${")) {
                val tagName = version.substring(2, version.length - 1)
                val tag = doc.getElementsByTagName(tagName).item(0)
                if (tag == null) {
                    logger.info("$artifactId has no version tag $tagName")
                    return@parallelForEach
                }
                version = tag.textContent
            }
            artifact.version = version
        }
        artifacts.add(artifact)
    }
    return artifacts
}


suspend fun <T> Iterable<T>.parallelForEach(action: suspend (T) -> Unit) = coroutineScope {
    forEach { element ->
        launch { action(element) }
    }
}
