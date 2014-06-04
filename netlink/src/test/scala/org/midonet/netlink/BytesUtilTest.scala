/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.netlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BytesUtilTest extends FunSpec with Matchers {

    val LE = ByteOrder.LITTLE_ENDIAN
    val BE = ByteOrder.BIG_ENDIAN

    val beRev = BytesUtil.Implementations.beReverser
    val leRev = BytesUtil.Implementations.leReverser

    val shortlist: List[Short] = List.fill(100) { Random.nextInt.toShort }
    val intlist: List[Int] = List.fill(100) { Random.nextInt }
    val longlist: List[Long] = List.fill(100) { Random.nextLong }

    describe("big-endian BytesUtil implementation") {

        it("should not reverse int, short and long values") {
            shortlist map { beRev reverseBE } shouldBe shortlist
            intlist map { beRev reverseBE } shouldBe intlist
            longlist map { beRev reverseBE } shouldBe longlist
        }

        it("should allocate buffers in BE order") {
            (beRev allocate 128).order shouldBe BE
            (beRev allocateDirect 128).order shouldBe BE
        }

        it("should create BE buffer slices regardless of original order") {
            (beRev sliceOf (beRev allocate 128)).order shouldBe BE
            (beRev sliceOf (leRev allocate 128)).order shouldBe BE
        }

        it("should read int arrays from ByteBuffers without reversing") {
            val buf = beRev allocate 128
            (0 to 5) foreach { buf putInt _ }
            buf.flip
            val ints = new Array[Int](6)
            beRev readBEIntsFrom (buf, ints)
            (0 to 5) foreach { i => ints(i) shouldBe i }
        }

        it("should write/and int arrays from ByteBuffers transparently") {
            val ints = Array.fill[Int](100) { Random.nextInt }
            val buf = beRev allocate (100 * 4)

            beRev writeBEIntsInto(buf, ints)
            buf.flip

            val readInts = new Array[Int](100)
            beRev readBEIntsFrom (buf, readInts)

            (0 to 99) foreach { i => ints(i) shouldBe readInts(i) }
        }
    }

    describe("little-endian BytesUtil implementation") {

        it("should reverse int, short and long values") {
            (leRev reverseBE 0xFEFF.toShort) shouldBe 0xFFFE.toShort
            (leRev reverseBE 0x00112233) shouldBe 0x33221100
            (leRev reverseBE 0x0011223344556677L) shouldBe 0x7766554433221100L

            shortlist.map { leRev reverseBE }
                     .map { leRev reverseBE } shouldBe shortlist

            intlist.map { leRev reverseBE }
                   .map { leRev reverseBE } shouldBe intlist

            longlist.map { leRev reverseBE }
                    .map { leRev reverseBE } shouldBe longlist
        }

        it("should allocate buffers in LE order") {
            (leRev allocate 128).order shouldBe LE
            (leRev allocateDirect 128).order shouldBe LE
        }

        it("should create LE buffer slices regardless of original order") {
            (leRev sliceOf (leRev allocate 128)).order shouldBe LE
            (leRev sliceOf (beRev allocate 128)).order shouldBe LE
        }

        it("should read and write int arrays from/into ByteBuffers") {
            val buf = leRev allocate 128
            (0 to 5) foreach { buf putInt _ }
            buf.flip
            val ints = new Array[Int](6)
            leRev readBEIntsFrom (buf, ints)
            (0 to 5) foreach { i => ints(i) shouldBe (leRev reverseBE i) }
        }

        it("should write/and int arrays from ByteBuffers transparently") {
            val ints = Array.fill[Int](100) { Random.nextInt }
            val buf = beRev allocate (100 * 4)

            beRev writeBEIntsInto(buf, ints)
            buf.flip

            val readInts = new Array[Int](100)
            beRev readBEIntsFrom (buf, readInts)

            (0 to 99) foreach { i => ints(i) shouldBe readInts(i) }
        }
    }
}
