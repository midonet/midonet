/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.lang.reflect.Type
import java.util.{UUID => JUUID}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{UUID => PUUID}

object UUID {

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

    implicit def fromProto(uuid: Commons.UUID): java.util.UUID = {
        new JUUID(uuid.getMsb, uuid.getLsb)
    }

    def randomUuidProto: PUUID = JUUID.randomUUID()

    sealed class Converter extends ZoomConvert.Converter[JUUID, PUUID] {
        override def toProto(value: JUUID, clazz: Type): PUUID =
            UUID.toProto(value)

        override def fromProto(value: PUUID, clazz: Type): JUUID =
            UUID.fromProto(value)
    }
}