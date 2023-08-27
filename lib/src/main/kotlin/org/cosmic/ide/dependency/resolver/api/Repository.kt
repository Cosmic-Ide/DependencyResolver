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

import java.net.HttpURLConnection
import java.net.URL

interface Repository {

    fun checkExists(artifact: Artifact): Boolean {
        val repository = getURL()
        val dependencyUrl =
            "$repository/${artifact.groupId.replace(".", "/")}/${artifact.artifactId}/maven-metadata.xml"
        val url = URL(dependencyUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        try {
            connection.connect()
        } catch (e: Exception) {
            return false
        }
        val isArtifactAvailable = connection.responseCode == 200
        if (isArtifactAvailable) {
            // Check if the version is available
            return url.readText().contains(artifact.version)
        }
        return false
    }

    fun getName(): String

    fun getURL(): String
}
