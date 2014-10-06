/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
