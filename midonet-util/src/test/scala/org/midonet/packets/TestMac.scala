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

package org.midonet.packets

import org.junit.runner.RunWith
import org.scalacheck.{Gen, Prop}
import org.scalatest.{Matchers, Suite}
import org.scalatest.junit.JUnitRunner
import org.scalatest.prop.Checkers

object TestMac {
    val NumOctetsInMac: Int = 6
    val NumBitsInOctet: Int = 8
}

@RunWith(classOf[JUnitRunner])
class TestMac extends Suite with Checkers with Matchers {
    import TestMac._

    // Random MAC address generator for ScalaCheck.
    val randomMacGen: Gen[MAC] = Gen.delay(MAC.random)

    def testConversions = check(Prop.forAll(randomMacGen) { (mac: MAC) =>
        val macStr: String = mac.toString
        val longFromString = MAC.stringToLong(macStr)
        val macByteArray: Array[Byte] = mac.getAddress
        val longFromBytes = MAC.bytesToLong(macByteArray)
        (macStr matches MAC.regex) &&
            // long <-> string
            (longFromString | MAC.MAC_MASK) == MAC.MAC_MASK &&
            macStr == MAC.longToString(longFromString) &&
            // long <-> byte[]
            (longFromBytes | MAC.MAC_MASK) == MAC.MAC_MASK &&
            // Deep equality check. Refer to the following link:
            //   http://stackoverflow.com/questions/5393243/
            macByteArray.deep == MAC.longToBytes(longFromBytes).deep &&
            // byte[] <-> string
            macStr == MAC.bytesToString(macByteArray) &&
            macByteArray.deep == MAC.stringToBytes(macStr).deep
    })

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

    def testGetSetAddressIsSame =
        check(Prop.forAll(randomMacGen) { (mac: MAC) =>
            mac == MAC.fromAddress(mac.getAddress)
        })

    def testGetSetStringIsSame = check(Prop.forAll(randomMacGen) { (mac: MAC) =>
        mac == MAC.fromString(mac.toString)
    })

    def testEqualOther = check(Prop.forAll(randomMacGen) { (mac: MAC) =>
        val args = List[Any]("foo", 4, Set(), Nil, List(1,2))
        !args.contains(mac)
    })

    def testUnitcast = check(Prop.forAll(randomMacGen) { (mac: MAC) =>
        val mask: Byte = (~0x1).toByte
        val bytes = mac.getAddress
        val firstByte = bytes.head
        bytes(0) = (firstByte & mask).toByte
        MAC.fromAddress(bytes).unicast
    })

    def testZeroMask = check(Prop.forAll(Gen.zip(randomMacGen, randomMacGen)) {
        case (randomMacA: MAC, randomMacB: MAC) =>
            randomMacA.equalsWithMask(randomMacB, 0L)
    })

    def testFullMask = check(Prop.forAll(randomMacGen) { (mac: MAC) =>
        // With no bits ignored, flipping any one bit in a MAC should
        // result in them being considered not equal.
        val checkList = for {
            i <- 0 until (NumBitsInOctet * NumOctetsInMac)
            // MAC with the i-th bit flipped not equal to the original.
            alteredBits = MAC.bytesToLong(mac.getAddress) ^ (1L << i)
            alteredMac = new MAC(MAC.longToBytes(alteredBits))
        }
        // Sanity check: MAC should always equal itself.
        yield mac.equalsWithMask(mac, MAC.MAC_MASK) &&
                !mac.equalsWithMask(alteredMac, MAC.MAC_MASK)
        checkList.forall(_ == true)
    })

    def testMaskedComparisonConsidersUnmaskedBits =
        check(Prop.forAll(randomMacGen) { (mac: MAC) =>
            val checkList = for {
                i <- 0 until NumOctetsInMac
                // Create a mask with only the bits in the (5 - i)th byte set.
                mask = 0xffL << ((5 - i) * NumBitsInOctet)
                // Use this mask to compare the original MAC to a MAC with
                // the bits in the same byte flipped. Should not be equal.
                bytes: Array[Byte] = mac.getAddress
                alteredBytes = bytes.updated(i, (bytes(i) ^ 0xff).toByte)
            }
            // Sanity check: Flip the bits back and verify equality.
            yield mac.equalsWithMask(new MAC(bytes), mask) &&
                    !mac.equalsWithMask(new MAC(alteredBytes), mask)
            checkList.forall(_ == true)
        })

    def testMaskedComparisonIgnoresMaskedBits =
        check(Prop.forAll(randomMacGen) { (mac: MAC) =>
            val checkList = for {
                i <- 0 until NumOctetsInMac
                // Create a mask with the (5 - i)th byte zeroed out.
                partialMask = ~(0xffL << ((5 - i) * NumBitsInOctet))
                // Create a MAC with the bits in the same byte flipped.
                bytes = mac.getAddress
                alteredBytes = bytes.updated(i, (bytes(i) ^ 0xff).toByte)
                alteredMac = new MAC(alteredBytes)
            } yield mac.equalsWithMask(alteredMac, partialMask) &&
                    !mac.equalsWithMask(alteredMac, MAC.MAC_MASK)
            checkList.forall(_ == true)
        })

    def testMidokuraOuiIsSet =
        check(Prop.forAll(randomMacGen) { (randomMac: MAC) =>
            val maskedMac: Long = randomMac.asLong & MAC.MIDOKURA_OUI_MASK
            maskedMac == MAC.MIDOKURA_OUI_MASK
        })

    def testMidokuraOuiIsTheSameForAllMacs =
        check(Prop.forAll(Gen.zip(randomMacGen, randomMacGen)) {
            case (randomMacA: MAC, randomMacB: MAC) =>
                val maskedMacA: Long = randomMacA.asLong & MAC.MIDOKURA_OUI_MASK
                val maskedMacB: Long = randomMacB.asLong & MAC.MIDOKURA_OUI_MASK
                maskedMacA == maskedMacB
        })
}
