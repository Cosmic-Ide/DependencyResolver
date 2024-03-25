package org.cosmic.ide.dependency.resolver

import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@OptIn(ExperimentalTime::class)
suspend fun main() {
    val artifact = getArtifact("com.google.android.material", "material", "1.12.0-beta01")
    val dir = File("test")
    dir.deleteRecursively()
    dir.mkdir()
    val time = measureTime {
        println("Starting...")
        artifact?.downloadArtifact(dir)
    }
    println("Total time: $time")
}
