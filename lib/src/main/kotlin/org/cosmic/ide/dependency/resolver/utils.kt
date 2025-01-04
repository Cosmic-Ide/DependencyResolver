/*
 *
 *  This file is part of Cosmic IDE.
 *  Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *  Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *  You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.cosmic.ide.dependency.resolver

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.cosmic.ide.dependency.resolver.api.Artifact
import org.cosmic.ide.dependency.resolver.api.EventReciever
import org.cosmic.ide.dependency.resolver.api.ProjectObjectModel
import org.cosmic.ide.dependency.resolver.api.Repository
import org.cosmic.ide.dependency.resolver.repository.GoogleMaven
import org.cosmic.ide.dependency.resolver.repository.Jitpack
import org.cosmic.ide.dependency.resolver.repository.MavenCentral
import org.cosmic.ide.dependency.resolver.repository.SonatypeSnapshots
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue

val repositories = ConcurrentLinkedQueue<Repository>().apply {
    addAll(listOf(MavenCentral(), GoogleMaven(), Jitpack(), SonatypeSnapshots()))
}
var eventReciever = EventReciever()
val okHttpClient = okhttp3.OkHttpClient()

val xmlDeserializer: ObjectMapper = XmlMapper(JacksonXmlModule().apply {
    setDefaultUseWrapper(false)
}).registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)

fun getArtifact(groupId: String, artifactId: String, version: String): Artifact? {
    val artifact = initHost(Artifact(groupId, artifactId, version)) ?: return null

    val pom = artifact.getPOM()!!
    artifact.extension = pom.packaging ?: "jar"

    return artifact
}

/*
 * Finds the host repository of the artifact and initialises it.
 * Returns null if no repository hosts this artifact
 */
fun initHost(artifact: Artifact): Artifact? {
    if (artifact.repository != null) {
        return artifact
    }
    for (repository in repositories) {
        if (repository.checkExists(artifact)) {
            artifact.repository = repository
            return artifact
        }
    }
    eventReciever.onArtifactNotFound(artifact)
    return null
}

suspend fun ProjectObjectModel.resolveDependencies(resolved: ConcurrentHashMap<Artifact, ConcurrentLinkedDeque<Artifact>>, managedDependencies: ConcurrentLinkedDeque<Artifact>) : ConcurrentLinkedDeque<Artifact> {
    managedDependencies.addAll(dependencyManagement?.dependencies.orEmpty().map {
        Artifact(it.groupId ?: groupId ?: parent!!.groupId, it.artifactId, it.version ?: "")
    })
    val deps = ConcurrentLinkedDeque<Artifact>()
    dependencies.orEmpty().filterNot {
        val invalidScope = it.scope == "test" || it.scope == "provided" || it.optional
        if (invalidScope) {
            eventReciever.onInvalidScope(Artifact(it.groupId ?: "", it.artifactId, it.version ?: ""), it.scope ?: "Optional")
        }
        return@filterNot invalidScope
    }.parallelForEach { dependency ->
        val previous =
            resolved.keys.find { a -> a.groupId == dependency.groupId && a.artifactId == dependency.artifactId }

        if (previous != null) {
            val artifact = Artifact(dependency.groupId ?: groupId ?: parent!!.groupId, dependency.artifactId, dependency.version?: "")
            if (needsVersionFix(dependency.version ?: "")) {
                initHost(artifact)
                fixVersion(artifact, properties, resolved)
            }
            if (getNewerVersion(previous.version, artifact.version) == previous.version) {
                deps.add(previous)
                return@parallelForEach
            }

            previous.version = artifact.version
            resolved[previous]?.forEach {
                it.dependencies?.find { a -> a.groupId == previous.groupId && a.artifactId == previous.artifactId }?.version =
                    previous.version
                println("Updating ${previous.artifactId} to ${previous.version} for ${it.artifactId}")
            }
            deps.add(previous)
            // again resolve this for newer version
            return@parallelForEach
        }
        val artifact = Artifact(dependency.groupId ?: groupId ?: parent!!.groupId, dependency.artifactId, dependency.version ?: "")
        eventReciever.onResolving(Artifact(groupId ?: parent!!.groupId, artifactId, version ?: parent!!.version), artifact)
        initHost(artifact)
        if (artifact.repository == null) {
            eventReciever.onArtifactNotFound(artifact)
            return@parallelForEach
        }
        if (needsVersionFix(dependency.version ?: "")) {
            fixVersion(artifact, properties, resolved)
        }
        eventReciever.onArtifactFound(artifact)
        managedDependencies.find { it.groupId == artifact.groupId && it.artifactId == artifact.artifactId }?.let {
            artifact.version = it.version
        }
        artifact.extension = artifact.getPOM()?.packaging ?: "jar"
        deps.add(artifact)
        eventReciever.onResolutionComplete(artifact)
    }
    return deps
}

private fun needsVersionFix(version: String): Boolean {
    return version.isEmpty() || version == "+" || version.startsWith("[") || version.startsWith("\${")
}

private fun fixVersion(artifact: Artifact, properties: Map<String, String>?, resolved: ConcurrentHashMap<Artifact, ConcurrentLinkedDeque<Artifact>> = ConcurrentHashMap()) {
    if (artifact.version.isEmpty() || artifact.version == "+") {
        eventReciever.onFetchingLatestVersion(artifact)
        artifact.version = resolved.keys.find { it.groupId == artifact.groupId && it.artifactId == artifact.artifactId }?.version?.takeIf { maxOf(it, artifact.version.substringBeforeLast("+")) == it } ?: artifact.mavenMetadata!!.versioning.let {
            it.release ?: it.latest ?: it.versions.lastOrNull() ?: artifact.version.substringBefore("+")
        }
        eventReciever.onFetchedLatestVersion(artifact, artifact.version)
    } else if (artifact.version.startsWith("[")) {
        artifact.version = getLatestRangeVersion(artifact, artifact.version, resolved)
    } else if (artifact.version.startsWith("\${")) {
        artifact.version = properties?.get(artifact.version.substring(2, artifact.version.length - 1)) ?: ""
    }
}

/*
 * Gets the latest version of the artifact from the given version range.
 *
 * @param artifact The artifact to get the latest version of.
 * @param version The version range to get the latest version from.
 * @return The latest version of the artifact.
 */
fun getLatestRangeVersion(artifact: Artifact, version: String, resolved: ConcurrentHashMap<Artifact, ConcurrentLinkedDeque<Artifact>> = ConcurrentHashMap()): String {
    if (!version.contains(",")) {
        return version.substring(1, version.length - 1)
    }
    val (start, end) = version.substring(1, version.length - 1).split(",")
    eventReciever.onFetchingLatestVersion(artifact)

    artifact.mavenMetadata!!.versioning.versions.forEach { v ->
        if (maxOf(v, start) == v && minOf(v, end) == v) {
            return v
        }
    }
    return resolved.keys.find { it.groupId == artifact.groupId && it.artifactId == artifact.artifactId }?.version?.takeIf { maxOf(it, artifact.version.substringBeforeLast("+")) == it } ?: artifact.mavenMetadata!!.versioning.let {
        it.release ?: it.latest ?: it.versions.lastOrNull() ?: artifact.version.substringBefore("+")
    }
}

fun getNewerVersion(existing: String, new: String): String {
    return if (maxOf(existing, new) == new) new else existing
}

/*
 * Runs the given action on each element of the iterable in parallel.
 * Returns a list of the results of the actions.
 *
 * @param action The action to run on each element.
 */
suspend fun <T> Iterable<T>.parallelForEach(action: suspend (T) -> Unit) = coroutineScope {
    map { element ->
        async { action(element) }
    }.awaitAll()
}
