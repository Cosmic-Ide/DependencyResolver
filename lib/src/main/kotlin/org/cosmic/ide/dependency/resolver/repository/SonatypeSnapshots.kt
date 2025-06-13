/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Cosmic IDE. If not, see <https://www.gnu.org/licenses/>.
 */

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
