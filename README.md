# DependencyResolver

Provides a simple API to check for, and download artifacts from Maven Central, Google Maven and Jitpack.
It was created as a lightweight alternative to Eclipse aether for Android. But this would work on any OS.

It is recommended to use snapshot builds from jitpack.

For checking if an artifact exists (in the above mentioned repositories), you can simply do
```kt
import org.cosmic.ide.dependency.resolver.getArtifact

val groupId = "com.squareup.retrofit2"
val artifactId = "retrofit"
val version = "2.9.0"

val artifact = getArtifact(groupId, artifactId, version)
val repository = artifact.repository
if (repository != null) {
    println("Artifact exists in ${ repository.getName() }")
} else {
    println("Cannot find artifact.")
}
```
NOTE: If you only want to download the artifact, you can use the `downloadTo` method instead.
For downloading an artifact with all of its dependencies, you can do
```kt
val output = File("<directory to download artifact>")
artifact.downloadArtifact(output)
```
