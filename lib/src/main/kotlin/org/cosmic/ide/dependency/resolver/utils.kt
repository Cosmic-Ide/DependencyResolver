/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Cosmic IDE. If not, see <https://www.gnu.org/licenses/>.
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
    artifact.extension = if (pom.packaging != null && pom.packaging != "bundle") pom.packaging else "jar"

    return artifact
}

/*
 * Finds the host repository of the artifact and initialises it.
 * Returns null if no repository hosts this artifact
 */
fun initHost(artifact: Artifact): Artifact? {
    if (artifact.repository != null) {
        return artifact // Already initialized or repository was set externally
    }
    // Attempt to find a repository only if not already set
    for (repository in repositories) {
        if (repository.checkExists(artifact)) {
            artifact.repository = repository
            return artifact
        }
    }
    eventReciever.onArtifactNotFound(artifact)
    return null
}

suspend fun ProjectObjectModel.resolveDependencies(
    resolved: ConcurrentHashMap<Pair<String, String>, Pair<Artifact, ConcurrentLinkedDeque<Artifact>>>,
    managedDependencies: ConcurrentLinkedDeque<Artifact>
): ConcurrentLinkedDeque<Artifact> {
    managedDependencies.addAll(dependencyManagement?.dependencies.orEmpty().map {
        // Ensure parent's GAV is used if child's GAV is missing
        val actualGroupId = it.groupId ?: groupId ?: parent?.groupId ?: throw IllegalStateException("GroupId missing for managed dependency ${it.artifactId}")
        Artifact(actualGroupId, it.artifactId, it.version ?: "")
    })
    val deps = ConcurrentLinkedDeque<Artifact>()
    dependencies.orEmpty().filterNot {
        val invalidScope = it.scope == "test" || it.scope == "provided" || it.optional
        if (invalidScope) {
            val depGroupId = it.groupId ?: groupId ?: parent?.groupId ?: "unknown.groupId"
            eventReciever.onInvalidScope(Artifact(depGroupId, it.artifactId, it.version ?: ""), it.scope ?: "Optional")
        }
        return@filterNot invalidScope
    }.parallelForEach { dependency ->
        val depGroupId = dependency.groupId ?: groupId ?: parent?.groupId ?: throw IllegalStateException("GroupId missing for dependency ${dependency.artifactId} in POM")
        val depArtifactId = dependency.artifactId

        // Create the artifact instance for this dependency
        val artifact = Artifact(depGroupId, depArtifactId, dependency.version ?: "")

        val originalGroupIdForEvent = this.groupId ?: parent?.groupId ?: "unknown.parent.groupId"
        val originalArtifactIdForEvent = this.artifactId
        val originalVersionForEvent = this.version ?: parent?.version ?: "unknown.parent.version"

        eventReciever.onResolving(Artifact(originalGroupIdForEvent, originalArtifactIdForEvent, originalVersionForEvent), artifact)

        // Initialize host repository for the artifact
        // This is crucial before version fixing or POM fetching
        initHost(artifact)

        if (artifact.repository == null && !needsVersionFix(artifact.version)) { // If repo is null and version doesn\'t need fixing, it's genuinely not found
            eventReciever.onArtifactNotFound(artifact)
            return@parallelForEach
        }

        // Apply version fixing (e.g., for '+', ranges, properties)
        // Pass 'resolved' map to 'fixVersion' in case it needs to look up other versions
        // (though direct modification of other versions is not the primary goal here).
        if (needsVersionFix(artifact.version)) {
            fixVersion(artifact, this, resolved)
            initHost(artifact) // Re-initialize host if version changed, as metadata might be different
        }

        // If, after version fixing, the repository is still null, then artifact is not found
        if (artifact.repository == null) {
            eventReciever.onArtifactNotFound(artifact)
            return@parallelForEach
        }

        eventReciever.artifactFound(artifact)

        // Apply managed dependencies
        managedDependencies.find { it.groupId == artifact.groupId && it.artifactId == artifact.artifactId }?.let { managedDep ->
            if (artifact.version != managedDep.version) { // Apply only if version is different
                eventReciever.logger.warning("Using managed dependency ${managedDep.groupId}:${managedDep.artifactId}:${managedDep.version} for ${artifact.groupId}:${artifact.artifactId} (was ${artifact.version})")
                artifact.version = managedDep.version
                // If managed version itself needs fixing (e.g. it was a property now resolved to '+'), or if repo might change
                initHost(artifact) // Re-init host for the new (managed) version.
                if (needsVersionFix(artifact.version)) {
                    fixVersion(artifact, this, resolved)
                    initHost(artifact) // Re-init host again after fixing.
                }
            }
        }

        // Get POM and set extension (packaging)
        val pom = artifact.getPOM() // getPOM might also call initHost if repository was null initially.
        artifact.extension = if (pom?.packaging != null && pom.packaging != "bundle") pom.packaging else "jar"

        deps.add(artifact)
        eventReciever.onResolutionComplete(artifact)
    }
    return deps
}

private fun needsVersionFix(version: String): Boolean {
    return version.isEmpty() || version == "+" || version.startsWith("[") || version.startsWith("\${'$'}{")
}

private fun fixVersion(
    artifact: Artifact,
    pom: ProjectObjectModel, // This is the POM of the 'artifact' whose version we are fixing
    resolved: ConcurrentHashMap<Pair<String, String>, Pair<Artifact, ConcurrentLinkedDeque<Artifact>>> = ConcurrentHashMap()
) {
    // Ensure metadata is loaded if needed for version fixing (e.g., for '+', ranges)
    if (artifact.repository == null) {
        initHost(artifact) // Initialize host if not already done
    }

    // If still no repo or metadata (for '+' or range), and version needs it, cannot fix version.
    if ((artifact.version.isEmpty() || artifact.version == "+" || artifact.version.startsWith("[")) && artifact.mavenMetadata == null && artifact.repository != null) {
        // Attempt to load metadata if it wasn't loaded by initHost or a previous checkExists
        if (artifact.repository?.checkExists(artifact) == false) {
            eventReciever.logger.warning("Could not load metadata for ${artifact.groupId}:${artifact.artifactId} to fix version \'${artifact.version}\'.")
            // Depending on the version type, we might still proceed or return
            if (artifact.version.isEmpty() || artifact.version == "+") return // Cannot fix '+' or empty without metadata
        }
    }

    if (artifact.version.isEmpty() || artifact.version == "+") {
        eventReciever.onFetchingLatestVersion(artifact)
        val latestFromMeta = artifact.mavenMetadata?.versioning?.let { it.release ?: it.latest ?: it.versions.lastOrNull() }
        artifact.version = latestFromMeta ?: artifact.version.substringBefore("+") // Fallback
        if (latestFromMeta == null) {
            eventReciever.logger.warning("Could not determine latest version for ${artifact.groupId}:${artifact.artifactId} from metadata. Using derived or existing: \'${artifact.version}\'.")
        }
        eventReciever.onFetchedLatestVersion(artifact, artifact.version)
    } else if (artifact.version.startsWith("[")) {
        // getLatestRangeVersion itself might need to be suspend if it loads metadata,
        // but it currently relies on artifact.mavenMetadata being pre-populated.
        // For safety, ensure metadata is loaded if we reach here and it's null.
        if (artifact.mavenMetadata == null && artifact.repository != null) {
            artifact.repository?.checkExists(artifact) // Attempt to load
        }
        artifact.version = getLatestRangeVersion(artifact, artifact.version, resolved)
    } else if (artifact.version.startsWith("\${'$'}{")) {
        val propertyName = artifact.version.substring(2, artifact.version.length - 1)
        var resolvedVersion: String? = null

        if (propertyName == "project.version") {
            // Refers to the current POM's version or its parent's GAV version.
            resolvedVersion = pom.version ?: pom.parent?.version
        } else {
            // Search in current POM properties, then parent POM properties recursively.
            var currentPomForProps: ProjectObjectModel? = pom
            while (currentPomForProps != null) {
                resolvedVersion = currentPomForProps.properties?.get(propertyName)
                if (resolvedVersion != null) break

                val parentGAV = currentPomForProps.parent
                currentPomForProps = if (parentGAV != null) {
                    val parentArtifact = Artifact(parentGAV.groupId, parentGAV.artifactId, parentGAV.version)
                    // Artifact.getPOM() is suspend and handles initHost internally.
                    parentArtifact.getPOM()
                } else {
                    null // No more parents
                }
            }
        }
        artifact.version = resolvedVersion ?: "" // Default to empty if property not found

        // If version resolved to another property or needs further fixing
        if (needsVersionFix(artifact.version) || artifact.version.startsWith("\${'$'}{")) {
            eventReciever.logger.warning("Version for ${artifact.groupId}:${artifact.artifactId} resolved from property \'${'$'}{${propertyName}}\' to \'${artifact.version}\'. Re-evaluating.")
            if (artifact.version.startsWith("\${'$'}{") && artifact.version.substring(2, artifact.version.length - 1) == propertyName) {
                eventReciever.logger.severe("Circular or unresolvable property ${artifact.version} for ${artifact.groupId}:${artifact.artifactId}")
                artifact.version = "" // Mark as unresolvable
            } else {
                // Recursively call fixVersion for the new value, using the original artifact's POM context
                fixVersion(artifact, pom, resolved)
            }
        }
    }
}

/*
 * Gets the latest version of the artifact from the given version range.
 *
 * @param artifact The artifact to get the latest version of.
 * @param version The version range to get the latest version from.
 * @return The latest version of the artifact.
 */
fun getLatestRangeVersion(
    artifact: Artifact,
    versionRange: String, // Renamed for clarity
    resolved: ConcurrentHashMap<Pair<String, String>, Pair<Artifact, ConcurrentLinkedDeque<Artifact>>> = ConcurrentHashMap()
): String {
    // Ensure metadata is available
    if (artifact.mavenMetadata == null) {
        initHost(artifact) // Attempt to load repo and metadata
        if (artifact.mavenMetadata == null) { // If still null
            eventReciever.logger.warning("Cannot determine latest range version for ${artifact.groupId}:${artifact.artifactId} (\'${versionRange}\') as maven metadata is missing.")
            // Fallback: try to extract a concrete version from the range, e.g., the lower bound if specified, or just return original.
            return versionRange.substringBefore(",").trimStart('[').trimEnd(']').ifEmpty { versionRange }
        }
    }

    val actualRange = versionRange.trim()
    if (!actualRange.startsWith("[") && !actualRange.startsWith("(")) { // Not a valid range string
        return actualRange // Assume it's a concrete version
    }
    if (!actualRange.contains(",")) { // e.g. [1.0.0] or (1.0.0)
        return actualRange.substring(1, actualRange.length - 1)
    }

    val parts = actualRange.substring(1, actualRange.length - 1).split(",")
    val startVersionString = parts.getOrNull(0)?.trim() ?: ""
    val endVersionString = parts.getOrNull(1)?.trim() ?: ""

    val startInclusive = actualRange.startsWith("[")
    val endInclusive = actualRange.endsWith("]")

    eventReciever.onFetchingLatestVersion(artifact) // For the range

    var bestVersion: String? = null
    // Iterate versions from metadata, assuming they are somewhat ordered, but explicitly compare.
    // Versions in metadata are usually strings, robust comparison is tricky.
    // This assumes getNewerVersion handles semantic versioning adequately.
    artifact.mavenMetadata!!.versioning.versions.forEach { v ->
        val vComparable = v // Assuming string comparison is okay via getNewerVersion

        val afterStart = when {
            startVersionString.isEmpty() -> true
            startInclusive -> getNewerVersion(vComparable, startVersionString) == vComparable || vComparable == startVersionString
            else -> getNewerVersion(vComparable, startVersionString) == vComparable && vComparable != startVersionString // strictly greater
        }

        val beforeEnd = when {
            endVersionString.isEmpty() -> true
            endInclusive -> getNewerVersion(vComparable, endVersionString) == endVersionString || vComparable == endVersionString
            else -> getNewerVersion(vComparable, endVersionString) == endVersionString && vComparable != endVersionString // strictly lesser
        }

        if (afterStart && beforeEnd) {
            if (bestVersion == null || getNewerVersion(bestVersion, vComparable) == vComparable) {
                bestVersion = vComparable
            }
        }
    }

    if (bestVersion != null) {
        eventReciever.onFetchedLatestVersion(artifact, bestVersion)
        return bestVersion
    }

    // Fallback if no version in range from metadata.
    eventReciever.logger.warning("No version found in metadata for range \'${actualRange}\' for ${artifact.groupId}:${artifact.artifactId}. Fallback might be used.")
    // Fallback to a version from 'resolved' if it fits the range, or a default part of the range.
    val resolvedVersionFromCache = resolved[Pair(artifact.groupId, artifact.artifactId)]?.first?.version
    if (resolvedVersionFromCache != null) {
         val afterStart = when {
            startVersionString.isEmpty() -> true
            startInclusive -> getNewerVersion(resolvedVersionFromCache, startVersionString) == resolvedVersionFromCache || resolvedVersionFromCache == startVersionString
            else -> getNewerVersion(resolvedVersionFromCache, startVersionString) == resolvedVersionFromCache && resolvedVersionFromCache != startVersionString
        }
        val beforeEnd = when {
            endVersionString.isEmpty() -> true
            endInclusive -> getNewerVersion(resolvedVersionFromCache, endVersionString) == endVersionString || resolvedVersionFromCache == endVersionString
            else -> getNewerVersion(resolvedVersionFromCache, endVersionString) == endVersionString && resolvedVersionFromCache != endVersionString
        }
        if (afterStart && beforeEnd) return resolvedVersionFromCache
    }

    return artifact.mavenMetadata!!.versioning.release
        ?: artifact.mavenMetadata!!.versioning.latest
        ?: startVersionString.takeIf { it.isNotEmpty() } // Default to start of range if specified
        ?: versionRange // Last resort original range string
}

fun getNewerVersion(existing: String, new: String): String {
    // This comparison needs to be robust for Maven versioning (e.g., 1.0, 1.0.0-alpha, 1.0.0-beta, 1.0.0-SNAPSHOT)
    // A simple string comparison is often NOT sufficient.
    // For now, placeholder:
    // Ideally, use a proper Maven ComparableVersion or similar library.
    // If not available, this is a known limitation.
    // Let's assume a simplified lexicographical comparison for now, favoring longer strings if prefixes match.
    if (new.startsWith(existing) && new.length > existing.length) return new // e.g. 1.10 vs 1.1
    if (existing.startsWith(new) && existing.length > new.length) return existing

    // Simple lexicographical might be okay for basic numeric versions like "1.2.3" vs "1.2.10"
    // but fails for "1.9" vs "1.10" (1.9 is "greater").
    // A proper implementation would parse segments.
    // For the sake of this example, and lacking a robust comparator in context:
    val newSegments = new.split('.', '-').mapNotNull { it.toIntOrNull() }
    val existingSegments = existing.split('.', '-').mapNotNull { it.toIntOrNull() }

    for (i in 0 until minOf(newSegments.size, existingSegments.size)) {
        if (newSegments[i] > existingSegments[i]) return new
        if (newSegments[i] < existingSegments[i]) return existing
    }
    // If one is prefix of other, the longer one is generally newer in numeric parts
    // e.g. 1.2.3 vs 1.2 ; 1.2.3 is newer
    if (newSegments.size > existingSegments.size) return new
    if (existingSegments.size > newSegments.size) return existing

    // If numeric parts are identical, consider qualifiers or original string length.
    // This is still very basic.
    return if (new >= existing) new else existing // Fallback to string comparison.
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

