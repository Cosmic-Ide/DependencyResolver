package org.cosmic.ide.dependency.resolver.api

import java.util.logging.Logger

open class EventReciever {

    val logger: Logger = Logger.getLogger("DependencyResolver")

    open fun onArtifactFound(artifact: Artifact) {
        logger.info("Found ${artifact.groupId}:${artifact.artifactId}:${artifact.version} in ${artifact.repository?.getName()}")
    }

    open fun onArtifactNotFound(artifact: Artifact) {
        logger.info("No repository contains ${artifact.groupId}:${artifact.artifactId}:${artifact.version}")
    }

    open fun onFetchingLatestVersion(artifact: Artifact) {
        logger.info("Fetching latest version of ${artifact.artifactId}")
    }

    open fun onFetchedLatestVersion(artifact: Artifact, version: String) {
        logger.info("Fetched latest version of ${artifact.artifactId}: $version")
    }

    open fun onResolving(artifact: Artifact, dependency: Artifact) {
        logger.info("Resolving $dependency from $artifact")
    }

    open fun onResolutionComplete(artifact: Artifact) {
        logger.info("Resolution complete for ${artifact.artifactId}")
    }

    open fun onSkippingResolution(artifact: Artifact) {
        logger.info("Skipping resolution of $artifact as it is already resolved")
    }

    open fun onVersionNotFound(artifact: Artifact) {
        logger.info("Version not found for ${artifact.artifactId}")
    }

    open fun onDependenciesNotFound(artifact: Artifact) {
        logger.info("No dependencies found for ${artifact.artifactId}")
    }

    open fun onInvalidScope(artifact: Artifact, scope: String) {
        logger.info("Invalid scope $scope for ${artifact.artifactId}")
    }

    open fun onInvalidPOM(artifact: Artifact) {
        logger.info("Invalid POM for ${artifact.artifactId}")
    }

    open fun onDownloadStart(artifact: Artifact) {
        logger.info("Starting download of ${artifact.artifactId}:${artifact.version}")
    }

    open fun onDownloadEnd(artifact: Artifact) {
        logger.info("Download complete for ${artifact.artifactId}:${artifact.version}")
    }

    open fun onDownloadError(artifact: Artifact, error: Throwable) {
        logger.severe("Error downloading ${artifact.artifactId}:${artifact.version}: ${error.message}")
    }
}
