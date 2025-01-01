package org.cosmic.ide.dependency.resolver.api

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

@JacksonXmlRootElement(localName = "metadata")
data class MavenMetadata(
    @JacksonXmlProperty(localName = "modelVersion", isAttribute = true)
    val modelVersion: String?,
    @JacksonXmlProperty(localName = "groupId")
    val groupId: String,
    @JacksonXmlProperty(localName = "artifactId")
    val artifactId: String,
    @JacksonXmlProperty(localName = "versioning")
    val versioning: Versioning
)

data class Versioning(
    @JacksonXmlProperty(localName = "latest")
    val latest: String,
    @JacksonXmlProperty(localName = "release")
    val release: String,
    @JacksonXmlProperty(localName = "versions")
    @get:JacksonXmlElementWrapper(useWrapping = false)
    val versionsList: Versions,
    @JacksonXmlProperty(localName = "lastUpdated")
    val lastUpdated: String
) {
    val versions: List<String>
        get() = versionsList.versionList
}

data class Versions(
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "version")
    val versionList: List<String>
)
