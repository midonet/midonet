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
package org.midonet.cluster.util

import java.lang.reflect.Type
import java.util.{UUID => JUUID}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons.{UUID => PUUID}

object UUIDUtil {

    /**
     * Convert a java.util.UUID to a Protocol Buffers message.
     */
    implicit def toProto(uuid: JUUID): PUUID = {
        if (uuid == null) null
        else PUUID.newBuilder
                  .setMsb(uuid.getMostSignificantBits)
                  .setLsb(uuid.getLeastSignificantBits)
                  .build()
    }

    def toProto(uuidStr: String): PUUID = {
        if (uuidStr == null) null
        else toProto(JUUID.fromString(uuidStr))
    }

    implicit def fromProto(uuid: PUUID): java.util.UUID = {
        new JUUID(uuid.getMsb, uuid.getLsb)
    }

    implicit def richJavaUuid(uuid: JUUID) = new {
        def asProto: PUUID = uuid
    }

    implicit def richProtoUuid(uuid: PUUID) = new {
        def asJava: JUUID = uuid
    }

    def randomUuidProto: PUUID = JUUID.randomUUID()

    sealed class Converter extends ZoomConvert.Converter[JUUID, PUUID] {
        override def toProto(value: JUUID, clazz: Type): PUUID =
            UUIDUtil.toProto(value)

        override def fromProto(value: PUUID, clazz: Type): JUUID =
            UUIDUtil.fromProto(value)
    }
}
