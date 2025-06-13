/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Cosmic IDE. If not, see <https://www.gnu.org/licenses/>.
 */

package org.cosmic.ide.dependency.resolver

import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@OptIn(ExperimentalTime::class)
suspend fun main() {
    val artifact = getArtifact("org.uberfire", "uberfire-nio2-jgit", "7.74.1.Final")
    val dir = File("test")
    dir.deleteRecursively()
    dir.mkdir()
    val time = measureTime {
        println("Starting...")
        artifact?.showDependencyTree()
        //artifact?.downloadArtifact(dir)
    }
    println("Total time: $time")
}
