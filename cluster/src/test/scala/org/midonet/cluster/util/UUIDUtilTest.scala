package org.midonet.cluster.util

import java.util.{UUID => JUUID}

import org.scalatest.FlatSpec

class UUIDUtilTest extends FlatSpec {

    import UUIDUtil._

    "Random UUIDs" should "translate back and forth correctly" in {
        1 to 10 foreach { i =>
            val uuid1 = JUUID.randomUUID()
            assert(uuid1 == UUIDUtil.fromProto(UUIDUtil.toProto(uuid1)))

            val uuid2 = java.util.UUID.randomUUID()
            assert(uuid2 == uuid2.asProto.asJava)
        }
    }
}
