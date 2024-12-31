/*
 *
 *  * This file is part of Cosmic IDE.
 *  * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *  * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *  * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 *
 *
 */

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.8.21"
    id("java-library")
    id("maven-publish")
    application
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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

application {
    mainClass.set("org.cosmic.ide.dependency.resolver.MainKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
}
