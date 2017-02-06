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
    val DIGITS = "0123456789"

    /**
      * Append the decimal representation of a long to a StringBuilder without
      * allocate objects
      *
      * @param b the string builder
      * @param i the long
      */
    def append(b: StringBuilder, i: Long): Unit = {
        var x = i
        // special case, as sign cannot be change for Long.MIN_VALUE
        if(x == Long.MinValue) {
            b.append(LONG_MIN_VALUE_STRING)
            return
        }
        // for negative, just print and change sign
        if(x < 0) {
            b.append('-')
            x = -x
        }
        // appending digits on reverse (first least significant ones)
        val base = 10L
        var start = b.length
        do {
            val d = (x % base).asInstanceOf[Int]
            b.append(DIGITS.charAt(d))
            x /= base
        } while(x > 0)
        var end = b.length - 1
        // un-reverse the digits by swapping start and end till needed
        while(start < end) {
            val c = b.charAt(start)
            b.setCharAt(start, b.charAt(end))
            b.setCharAt(end, c)
            start += 1
            end -= 1
        }
    }

    def append(b: StringBuilder, s: String): Unit = {
        b.append(s)
    }

    def append(b: StringBuilder, c: Char): Unit = {
        b.append(c)
    }
}