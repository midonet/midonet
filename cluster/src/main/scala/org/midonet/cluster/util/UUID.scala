package org.midonet.cluster.util


import org.midonet.cluster.models.Commons

object UUID {

    /** Convert a java.util.UUID to a Protobufs object */
    implicit def toProto(uuid: java.util.UUID): Commons.UUID = {
        if (uuid == null) null
        else  Commons.UUID.newBuilder
                          .setMsb(uuid.getMostSignificantBits)
                          .setLsb(uuid.getLeastSignificantBits)
                          .build()
    }

    implicit def fromProto(uuid: Commons.UUID): java.util.UUID = {
        new java.util.UUID(uuid.getMsb, uuid.getLsb)
    }

    def randomUuidProto = {
        val uuid = java.util.UUID.randomUUID;
        Commons.UUID.newBuilder.setMsb(uuid.getMostSignificantBits)
                               .setLsb(uuid.getLeastSignificantBits)
                               .build()
    }
}