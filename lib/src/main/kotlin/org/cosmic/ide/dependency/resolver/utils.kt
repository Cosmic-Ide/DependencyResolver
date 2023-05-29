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

import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.cosmic.ide.dependency.resolver.api.Artifact
import org.cosmic.ide.dependency.resolver.repository.*
import org.w3c.dom.Element
import java.util.logging.Logger

val repositories by lazy { listOf(MavenCentral(), Jitpack(), GoogleMaven()) }
val logger = Logger.getAnonymousLogger()

fun getArtifact(groupId: String, artifactId: String, version: String): Artifact {
    val artifact = initHost(Artifact(groupId, artifactId, version))
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(artifact?.getPOM()!!)

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
        if (repository.checkExists(artifact.groupId, artifact.artifactId)) {
            logger.info("Found ${artifact.artifactId} in ${repository.getName()}")
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
fun InputStream.resolvePOM(): List<Artifact> {
    val artifacts = mutableListOf<Artifact>()
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(this)

    val dependencies = doc.getElementsByTagName("dependencies").item(0) as Element?
        ?: return artifacts
    val dependencyElements = dependencies.getElementsByTagName("dependency")
    val packaging = dependencies.getElementsByTagName("packaging").item(0).textContent
    for (i in 0 until dependencyElements.length) {
        val dependencyElement = dependencyElements.item(i) as Element
        val scopeItem = dependencyElement.getElementsByTagName("scope").item(0)
        if (scopeItem != null) {
            val scope = scopeItem.textContent
            // if scope is test/provided, there is no need to download them
            if (scope.isNotEmpty() && (scope == "test" || scope == "provided")) {
                continue
            }
        }
        val groupId = dependencyElement.getElementsByTagName("groupId").item(0).textContent
        val artifactId = dependencyElement.getElementsByTagName("artifactId").item(0).textContent
        if (artifactId.endsWith("bom")) {
            // TODO: handle versions from BOMs
            continue
        }
        val artifact = Artifact(groupId, artifactId, extension=packaging)
        initHost(artifact)
        if (artifact.repository == null) {
            continue
        }

        val metadata = artifact.getMavenMetadata()
        val item = dependencyElement.getElementsByTagName("version").item(0)
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
                if (version.contains("[")) {
                    continue
                }
            }
            artifact.version = version
        }
        artifacts.add(artifact)
    }
    return artifacts
}
