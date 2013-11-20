/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.packets

import org.junit.runner.RunWith
import org.scalatest.{Matchers, Suite}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestMac extends Suite with Matchers {

    val macpool = List.tabulate(1000) { _ => MAC.random }

    def testConversions {
        val mask = 0xffffffffffffL
        for (m <- macpool; s = m.toString; ary = m.getAddress) {

            // long <-> string
            val longFromString = MAC.stringToLong(s)
            (longFromString | mask) should be (mask)
            s should be (MAC.longToString(longFromString))

            // long <-> byte[]
            val longFromBytes = MAC.bytesToLong(ary)
            (longFromBytes | mask) should be (mask)
            ary should be (MAC.longToBytes(longFromBytes))

            // byte[] <-> string
            s should be (MAC.bytesToString(ary))
            ary should be (MAC.stringToBytes(s))
        }
    }

    def testConversionsException {
        //byte[] -> long / string
        List[Array[Byte]](
            null,
            Array[Byte](1,2,3,4,5),
            Array[Byte](1,2,3,4,5,6,7)
        ).foreach { array =>
            intercept[IllegalArgumentException] { MAC.bytesToLong(array) }
            intercept[IllegalArgumentException] { MAC.bytesToString(array) }
        }

        // string -> long / byte[]
        List[String](
            null,
            "eewofihewiofh",
            "ww:ww:ww:ww:ww:ww",
            "01:23:45:ww:67:89",
            "01:23:45::67:89",
            "01:23:45:21324:67:89"
        ).foreach { str =>
            intercept[IllegalArgumentException] { MAC.stringToLong(str) }
            intercept[IllegalArgumentException] { MAC.stringToBytes(str) }
        }
    }

    def testStringConversionWithPadding {
        List[(String,String)](
            ("01:02:03:04:05:06", "01:02:03:04:05:6"),
            ("01:02:03:04:05:06", "01:02:03:04:5:6"),
            ("01:02:03:04:05:06", "01:2:3:04:5:6"),
            ("01:00:03:04:05:06", "01:0:3:04:5:6")
        ).foreach{ case (s1,s2) =>
            MAC.stringToLong(s1) should be (MAC.stringToLong(s2))
            MAC.stringToBytes(s1) should be (MAC.stringToBytes(s2))
        }
    }


    def testGetSetAddressIsSame {
        for (m <- macpool) { m should be (MAC fromAddress m.getAddress) }
    }

    def testGetSetStringIsSame {
        for (m <- macpool) { m should be (MAC fromString m.toString) }
    }

    def testCloneIsSame {
        for (m <- macpool) { m should be (m.clone) }
    }

    def testEqualOther {
        val args = List[Any]("foo", 4, Set(), Nil, List(1,2))
        for (m <- macpool; x <- args) { m should not be (x) }
    }

    def testUnitcast {
        val mask: Byte = ~0x1
        for (m <- macpool) {
            val bytes = m.getAddress
            val firstByte = bytes(0)
            bytes(0) = (firstByte & mask).toByte
            (MAC.fromAddress(bytes).unicast) should be (true)
        }

    }

    def testInSets {
        val mset = macpool.toSet
        mset.size should be (macpool.size)
        for (m <- macpool) { mset.contains(m) should be (true) }
    }

    def testInHashes {
        val mmap = macpool.foldLeft(Map[MAC,String]()) {
            (a,m) => a + (m -> m.toString)
        }
        mmap.size should be (macpool.size)
        for (m <- macpool) { mmap.get(m) should be (Some(m.toString)) }
    }

}
