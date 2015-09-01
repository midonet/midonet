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

package org.midonet.util

import java.util.UUID

object UUIDUtil {

    /**
     * This method generates a new UUID object from XOR'ing two UUIDs.
     */
    def xor(id1: UUID, id2: UUID): UUID = {
        // These bits are metadata and should not be flipped.
        val msbMask = id2.getMostSignificantBits & 0xffffffffffff0fffL
        val lsbMask = id2.getLeastSignificantBits & 0x3fffffffffffffffL

        new UUID(id1.getMostSignificantBits ^ msbMask,
                 id1.getLeastSignificantBits ^ lsbMask)
    }

    /**
     * This method generates a new UUID object from XOR'ing variable number of
     * UUIDs.
     */
    def xor(ids: UUID*): UUID = {
        ids.reduceLeft(xor)
    }
}
