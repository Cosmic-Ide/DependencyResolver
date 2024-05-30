package org.cosmic.ide.dependency.resolver

import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@OptIn(ExperimentalTime::class)
suspend fun main() {
    val artifact = getArtifact("com.onesignal", "OneSignal", "5.1.13")
    val dir = File("test")
    dir.deleteRecursively()
    dir.mkdir()
    val time = measureTime {
        println("Starting...")
        artifact?.resolve()
    }
    println("Total time: $time")
}
