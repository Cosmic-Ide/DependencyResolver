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
import java.net.HttpURLConnection
import java.net.URL

interface Repository {

    fun checkExists(groupId: String, artifactId: String): Boolean {
        val repository = getURL()
        val dependencyUrl =
            "$repository/${groupId.replace(".", "/")}/$artifactId/maven-metadata.xml"
        val url = URL(dependencyUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        val responseCode = connection.responseCode == 200
        return responseCode
    }

    fun checkExists(artifact: Artifact): Boolean {
        return checkExists(artifact.groupId, artifact.artifactId)
    }

    fun getName(): String

    fun getURL(): String
}
