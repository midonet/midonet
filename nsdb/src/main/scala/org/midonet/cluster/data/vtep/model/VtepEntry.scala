/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.data.vtep.model

import java.util.{Objects, UUID}

/**
 * A class to represent a VTEP's table entry
 */
abstract class VtepEntry {
    /** All entries from vtep tables must have a uuid */
    val uuid: UUID

    /** The hash code is based on uuid */
    override def hashCode: Int = if (uuid == null) 0 else uuid.hashCode()
}
