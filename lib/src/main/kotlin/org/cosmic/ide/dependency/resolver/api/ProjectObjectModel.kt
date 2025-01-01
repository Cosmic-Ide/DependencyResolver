package org.cosmic.ide.dependency.resolver.api

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

@JacksonXmlRootElement(localName = "project")
data class ProjectObjectModel(
    @JacksonXmlProperty(localName = "modelVersion") val modelVersion: String?,
    @JacksonXmlProperty(localName = "parent") val parent: Parent?,
    @JacksonXmlProperty(localName = "groupId") val groupId: String?,
    @JacksonXmlProperty(localName = "artifactId") val artifactId: String,
    @JacksonXmlProperty(localName = "version") val version: String?,
    @JacksonXmlProperty(localName = "packaging") val packaging: String?,
    @JacksonXmlProperty(localName = "name") val name: String?,
    @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "license") val licenses: List<License>?,

    @JacksonXmlProperty(localName = "dependencyManagement") val dependencyManagement: DependencyManagement?,
    @JacksonXmlProperty(localName = "dependencies")
    @JacksonXmlElementWrapper(localName = "dependencies", useWrapping = true)
    val dependencies: List<Dependency>?,

    @JacksonXmlProperty(localName = "properties")
    @JacksonXmlElementWrapper(localName = "properties", useWrapping = true)
    val properties: Map<String, String>?,

    @JacksonXmlProperty(localName = "build") val build: Build?,
    @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "profile") val profiles: List<Profile>?
)

data class DependencyManagement(
    @JacksonXmlProperty(localName = "dependencies")
    @JacksonXmlElementWrapper(localName = "dependencies", useWrapping = true)
    val dependencies: List<Dependency>,
    )

data class Parent(
    @JacksonXmlProperty(localName = "groupId") val groupId: String,
    @JacksonXmlProperty(localName = "artifactId") val artifactId: String,
    @JacksonXmlProperty(localName = "version") val version: String
)

data class License(
    @JacksonXmlProperty(localName = "name") val name: String,
    @JacksonXmlProperty(localName = "url") val url: String
)

data class Dependency(
    @JacksonXmlProperty(localName = "groupId")
    val groupId: String?,
    @JacksonXmlProperty(localName = "artifactId")
    val artifactId: String,
    @JacksonXmlProperty(localName = "version")
    val version: String?,
    @JacksonXmlProperty(localName = "scope")
    val scope: String?
) {
    override fun toString(): String {
        return "$groupId:$artifactId:$version"
    }
}

data class Build(
    @get:JacksonXmlElementWrapper(useWrapping = false) @get:JacksonXmlProperty(localName = "plugin") val plugins: List<Plugin>?
)

data class Plugin(
    @JacksonXmlProperty(localName = "groupId") val groupId: String?,
    @JacksonXmlProperty(localName = "artifactId") val artifactId: String?,
    @JacksonXmlProperty(localName = "version") val version: String?,
    @get:JacksonXmlElementWrapper(useWrapping = false) @get:JacksonXmlProperty(localName = "execution") val executions: List<Execution>?
)

data class Execution(
    @JacksonXmlProperty(localName = "id") val id: String?,
    @get:JacksonXmlElementWrapper(useWrapping = false) @get:JacksonXmlProperty(localName = "goal") val goals: List<String>?
)

data class Profile(
    @JacksonXmlProperty(localName = "id") val id: String?,
    @JacksonXmlProperty(localName = "activation") val activation: Activation?,
    @get:JacksonXmlElementWrapper(useWrapping = false) @get:JacksonXmlProperty(localName = "property") val properties: Map<String, String>?
)

data class Activation(
    @JacksonXmlProperty(localName = "jdk") val jdk: String?
)


