package org.cosmic.ide.dependency.resolver

import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@OptIn(ExperimentalTime::class)
suspend fun main() {
    val artifact = getArtifact("com.google.android.gms", "play-services-ads", "23.6.0")
    val dir = File("test")
    dir.deleteRecursively()
    dir.mkdir()
    val time = measureTime {
        println("Starting...")
        artifact?.showDependencyTree()
        artifact?.downloadArtifact(dir)
    }
    println("Total time: $time")
}
