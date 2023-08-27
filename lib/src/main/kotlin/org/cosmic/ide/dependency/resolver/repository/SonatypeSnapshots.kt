package org.cosmic.ide.dependency.resolver.repository

import org.cosmic.ide.dependency.resolver.api.Repository

class SonatypeSnapshots: Repository {
    override fun getName(): String {
        return "Sonatype Snapshots"
    }

    override fun getURL(): String {
        return "https://s01.oss.sonatype.org/content/repositories/snapshots"
    }
}