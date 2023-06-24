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
import org.cosmic.ide.dependency.resolver.resolvePOM
import java.io.File
import java.io.InputStream
import java.net.URL
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
        val stream = URL(dependencyUrl).openConnection().inputStream
        output.outputStream().use { stream.copyTo(it) }
    }

    fun downloadArtifact(output: File) {
        output.mkdirs()
        val artifacts = resolve()
        artifacts.add(this)

        val latestDeps =
            artifacts.groupBy { it.groupId to it.artifactId }.values.map { artifact -> artifact.maxBy { it.version } }

        latestDeps.forEach { art ->
            if (art.version.isNotEmpty() && art.repository != null) {
                art.downloadTo(File(output, "${ art.artifactId }-${ art.version }" + "." + extension))
            }
        }
    }

    fun resolve(): MutableList<Artifact> {
        val pom = getPOM() ?: return mutableListOf()
        val deps = pom.resolvePOM()
        val artifacts = mutableListOf<Artifact>()
        deps.forEach { dep ->
            logger.info("Resolving ${dep.groupId}:${dep.artifactId}")
            if (dep.version.isEmpty()) {
                logger.info("Fetching latest version of ${dep.artifactId}")
                val meta = URL("${ dep.repository!!.getURL() }/${ dep.groupId.replace(".", "/") }/${ dep.artifactId }/maven-metadata.xml").openConnection().inputStream
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(meta)
                val v = doc.getElementsByTagName("release").item(0)
                if (v != null) {
                    dep.version = v.textContent
                    logger.info("Latest version of ${dep.groupId}:${dep.artifactId} is ${dep.version}")
                }
            }
            logger.info("Resolved ${dep.groupId}:${dep.artifactId}")
            artifacts.add(dep)
            artifacts.addAll(dep.resolve())
        }
        return artifacts
    }

    fun getMavenMetadata(): String {
        if (repository == null) {
            return ""
        }
        val dependencyUrl =
            "${repository?.getURL()}/${groupId.replace(".", "/")}/${artifactId}/maven-metadata.xml"
        return URL(dependencyUrl).readText()
    }


    fun getPOM(): InputStream? {
        val pomUrl =
            "${ repository!!.getURL() }/${ groupId.replace(".", "/") }/$artifactId/$version/$artifactId-$version.pom"
        if (version.isNotEmpty()) {
            return URL(pomUrl).openConnection().inputStream
        }
        return null
    }
}
