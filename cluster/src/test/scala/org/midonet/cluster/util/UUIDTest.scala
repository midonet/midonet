package org.midonet.cluster.util

import org.scalatest.FlatSpec

class UUIDTest extends FlatSpec {

    "Random UUIDs" should "translate back and forth correctly" in {
        1 to 10 foreach { i =>
            val uuid = java.util.UUID.randomUUID()
            assert(uuid == UUID.fromProto(UUID.toProto(uuid)))
        }
    }
}
