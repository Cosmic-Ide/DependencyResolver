plugins {
    id("org.jetbrains.kotlin.jvm") version "1.8.10"
    id("maven-publish")
}

repositories {
    mavenCentral()
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      groupId = "org.cosmic.ide"
      artifactId = "dependency-resolver"
      version = "1.0.2"

      from(components["java"])
    }
  }
}