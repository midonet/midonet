/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.packets;

import org.junit.runner.RunWith
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

@RunWith(classOf[JUnitRunner])
class TestMac extends Suite with ShouldMatchers {

    val macpool = List.tabulate(1000) { _ => MAC.random }

    def testGetSetAddressIsSame {
        for (m1 <- macpool) { m1 should be (MAC fromAddress m1.getAddress) }
    }

    def testGetSetStringIsSame {
        for (m1 <- macpool) { m1 should be (MAC fromString m1.toString) }
    }

    def testCloneIsSame {
        for (m1 <- macpool) { m1 should be (m1.clone) }
    }

    def testEqualOther {
        val args = List[Any]("foo", 4, Set(), Nil, List(1,2))
        for (m1 <- macpool; x <- args) { m1 should not be (x) }
    }

    def testUnitcast {
        val mask: Byte = ~0x1
        for (m1 <- macpool) {
            val bytes = m1.getAddress
            val firstByte = bytes(0)
            bytes(0) = (firstByte & mask).toByte
            (MAC.fromAddress(bytes).unicast) should be (true)
        }

    }

    def testInSets {
        val mset = macpool.toSet
        mset.size should be (macpool.size)
        for (m1 <- macpool) { mset.contains(m1) should be (true) }
    }

    def testInHashes {
        val mmap = macpool.foldLeft(Map[MAC,String]()) {
            (a,m) => a + (m -> m.toString)
        }
        mmap.size should be (macpool.size)
        for (m1 <- macpool) { mmap.get(m1) should be (Some(m1.toString)) }
    }

}
