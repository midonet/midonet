package org.midonet.cluster.util

import java.util.UUID

import org.midonet.cluster.models.Commons

object UUID {

    /** Convert a java.util.UUID to a Protobufs object */
    implicit def toProto(uuid: UUID): Commons.UUID = {
        if (uuid == null) null
        else  Commons.UUID.newBuilder()
                          .setMsb(uuid.getMostSignificantBits)
                          .setLsb(uuid.getLeastSignificantBits)
                          .build()
    }

    implicit def fromProto(uuid: Commons.UUID): UUID = {
        new UUID(uuid.getMsb, uuid.getLsb)
    }

}