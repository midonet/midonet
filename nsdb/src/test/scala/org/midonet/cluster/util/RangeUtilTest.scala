/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.util

import org.junit.runner.RunWith
import org.midonet.cluster.models.Commons.Int32Range
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FlatSpec}

import org.midonet.util.Range

@RunWith(classOf[JUnitRunner])
class RangeUtilTest extends FlatSpec with Matchers {

    private def verifyConversion(proto: Int32Range, start: Integer,
                                 end: Integer): Unit = {
        proto.hasStart shouldBe (start ne null)
        proto.hasEnd shouldBe (end ne null)
        proto.getStart shouldBe (if (start ne null) start else 0)
        proto.getEnd shouldBe (if (end ne null) end else 0)
    }

    private def testConversion(start: Integer, end: Integer): Unit = {
        val range = new Range[Integer](start, end)
        val proto = RangeUtil.toProto(range)

        verifyConversion(proto, start, end)

        val pojo = RangeUtil.fromProto(proto)

        pojo shouldBe range
    }

    "Range with start < end" should "convert to/from Protocol Buffers" in {
        testConversion(10, 100)
    }

    "Range with start == end" should "convert to/from Protocol Buffers" in {
        testConversion(10, 10)
    }

    "Range with start > end" should "fail converting to/from Protocol Buffers" in {
        intercept[IllegalArgumentException] {
            testConversion(100, 10)
        }
    }

    "Range with start == null" should "convert to/from Protocol Buffers" in {
        testConversion(null, 10)
    }

    "Range with end == null" should "convert to/from Protocol Buffers" in {
        testConversion(10, null)
    }

    "Range with start/end == null" should "convert to/from Protocol Buffers" in {
        testConversion(null, null)
    }

    "String range with a single value" should "convert to to/from Protocol Buffers" in {
        val proto = RangeUtil.strToInt32Range("10")
        verifyConversion(proto, 10, 10)
    }

    "String range with a range value" should "convert to to/from Protocol Buffers" in {
        val proto = RangeUtil.strToInt32Range("10:100")
        verifyConversion(proto, 10, 100)
    }

    "Invalid string ranges" should "throw proper exceptions" in {
        intercept[NullPointerException] {
            RangeUtil.strToInt32Range(null)
        }
        intercept[IllegalArgumentException] {
            RangeUtil.strToInt32Range("10:100:1000")
        }
        intercept[NumberFormatException] {
            RangeUtil.strToInt32Range("")
        }
        intercept[NumberFormatException] {
            RangeUtil.strToInt32Range("10-100")
        }
        intercept[IllegalArgumentException] {
            // ":".split(':') returns an array of length 0
            RangeUtil.strToInt32Range(":")
        }
    }
}
