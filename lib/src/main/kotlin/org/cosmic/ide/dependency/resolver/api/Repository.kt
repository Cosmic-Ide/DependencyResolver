package org.cosmic.ide.dependency.resolver.api

import java.net.HttpURLConnection
import java.net.URL

interface Repository {

    fun checkExists(groupId: String, artifactId: String): Boolean {
        val repository = getURL()
        val dependencyUrl = "$repository/${groupId.replace(".", "/")}/$artifactId/maven-metadata.xml"
        val url = URL(dependencyUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        return connection.responseCode == 200
    }

    fun checkExists(artifact: Artifact): Boolean {
        return checkExists(artifact.groupId, artifact.artifactId)
    }

    fun getName(): String

    fun getURL(): String
}
