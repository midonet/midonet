/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.cluster

import java.util.UUID

import org.midonet.cluster.models.Commons

package object data {
    type Obj = Object
    type ObjId = Any

    /**
      * @return The given ZOOM object identifier as a UUID string. If the
      *         argument is already a string, the method returns the argument
      *         without validation. Otherwise, the argument must be a
      *         [[java.util.UUID]] or a Protocol Buffers [[Commons.UUID]]. In
      *         both cases, the method returns their value as a string in the
      *         UUID format.
      */
    def getIdString(id: ObjId): String = {

        def digits(value: Long, digits: Long): String = {
            val hi = 1L << (digits * 4)
            java.lang.Long.toHexString(hi | (value & (hi - 1))).substring(1)
        }

        id match {
            case s: String => s
            case uuid: UUID => uuid.toString
            case uuid: Commons.UUID =>
                digits(uuid.getMsb >> 32, 8) + "-" +
                digits(uuid.getMsb >> 16, 4) + "-" +
                digits(uuid.getMsb, 4) + "-" +
                digits(uuid.getLsb >> 48, 4) + "-" +
                digits(uuid.getLsb, 12)
            case _ =>
                throw new IllegalArgumentException(s"Invalid ZOOM identifier $id")
        }
    }

}
