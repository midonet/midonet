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

package org.midonet.util

import java.io.BufferedWriter

object StringUtil {

    /**
     * Returns o.toString with each line indented by the specified number of
     * spaces.
     *
     * Be aware that this will add trailing spaces if o.toString ends with a
     * newline.
     */
    def indent(o: Object, spaces: Int) = {
        val margin = " " * spaces
        margin + o.toString.replaceAllLiterally("\n", "\n" + margin)
    }

    /** Returns null if o is null, otherwise o.toString. */
    def toStringOrNull(o: AnyRef) = if (o == null) null else o.toString

    val LONG_MIN_VALUE_STRING = Long.MinValue.toString
    val BASE = 10
    val DIGITS = "0123456789".toCharArray()
    val MAX_NUMBER_OF_DIGITS = 19
    val LEFTMOST_DIGIT_UNITARY_VALUE = 1000000000000000000L

    // Assert DIGITS, MAX_NUMBER_OF_DIGITS and LEFTMOST_DIGIT_UNITARY_VALUE
    // values, by comparing them with calculated ones, but avoid the
    // calculation cost during initialization for production code.
    assert( {for (i <- 0 to BASE - 1) { assert(DIGITS(i) == i.toString.charAt(0)) }; true })
    assert(MAX_NUMBER_OF_DIGITS == Math.ceil(Math.log(Math.max(
               Math.abs(Long.MinValue.asInstanceOf[Double]),
               Math.abs(Long.MaxValue.asInstanceOf[Double])
           ))/Math.log(BASE)).asInstanceOf[Int])
    assert(LEFTMOST_DIGIT_UNITARY_VALUE ==
           ("1" + ("0" * (MAX_NUMBER_OF_DIGITS - 1))).toLong)

    /**
      * Append the decimal representation of a long to a StringBuilder without
      * allocate objects
      *
      * @param b the buffered writer
      * @param i the long
      */
    def append(b: BufferedWriter, i: Long): BufferedWriter = {
        var x = i
        // special case, as sign cannot be change for Long.MIN_VALUE
        if (x == Long.MinValue) {
            b.append(LONG_MIN_VALUE_STRING)
            return b
        }
        // special case, as zero has "one leading zero" special case and this
        // way we can avoid to check "to go too far" in the loop skip leading
        // zeros loop
        if (x == 0) {
            b.append('0')
            return b
        }
        // for negative, just print and change sign
        if (x < 0) {
            b.append('-')
            x = -x
        }
        var currentDigitUnitaryValue = LEFTMOST_DIGIT_UNITARY_VALUE
        var digit = (x / currentDigitUnitaryValue).asInstanceOf[Int]
        // loop to skip leading zeros
        while (digit == 0) {
            // append nothing
            // get next digit
            x %= currentDigitUnitaryValue
            currentDigitUnitaryValue /= BASE
            digit = (x / currentDigitUnitaryValue).asInstanceOf[Int]
        }
        // loop for digits at positions greater than unit
        while (currentDigitUnitaryValue > 1) {
            // append digit
            b.append(DIGITS(digit))
            // get next digit
            x %= currentDigitUnitaryValue
            currentDigitUnitaryValue /= BASE
            digit = (x / currentDigitUnitaryValue).asInstanceOf[Int]
        }
        // print digit at units position as a special case out of the loop to
        // avoid further unneeded divisions
        b.append(DIGITS(digit))
        b
    }
}