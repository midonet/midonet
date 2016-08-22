/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.cluster.services.state;

import org.midonet.packets.MAC;

public final class FastDecoders {

    /**
     * Parses part of a [[String]] as a number in hexadecimal base
     * @param s Source [[String]]
     * @param a Start of range to parse
     * @param b Last character to parse
     * @return The number
     * @throws IllegalArgumentException if a non-hexadecimal character is found
     * in the given range
     */
    private static long hexToLong(String s, int a, int b) {
        long v = 0L;
        for (int i = a; i <= b; ++i) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                v = (v << 4) | (c - '0');
            } else if (c >= 'a' && c <= 'f') {
                v = (v << 4) | (c - 'a' + 0xa);
            } else if (c >= 'A' && c <= 'F') {
                v = (v << 4) | (c - 'A' + 0xa);
            } else {
                throw new IllegalArgumentException(
                    "Invalid character " + c +
                    " at position " + i + "in string: " + s);
            }
        }
        return v;
    }

    /** This class provides a mutable version of the [[UUID]] from Java.util
     */
    public static class MutableUUID {

        public long msb;
        public long lsb;

        public MutableUUID() {
            msb = 0;
            lsb = 0;
        }

        public long getMostSignificantBits() {
            return msb;
        }

        public long getLeastSignificantBits() {
            return lsb;
        }

        /** Fills this UUID from the contents of a string
         * @throws IllegalArgumentException if passed an invalid UUID string
         * UUID.
         */
        public void fillFromString(String s) {
            if (s.length() != 36 || s.charAt(8) != '-'
                || s.charAt(13) != '-' || s.charAt(18) != '-'
                || s.charAt(23) != '-') {
                throw new IllegalArgumentException("Invalid UUID string: " + s);
            }
            this.msb = (hexToLong(s, 0, 7) << 32)
                       | (hexToLong(s, 9, 12) << 16)
                       | hexToLong(s, 14, 17);
            this.lsb = (hexToLong(s, 19, 22) << 48)
                       | hexToLong(s, 24, 35);
        }
    }

    private static MAC.InvalidMacException illegalMacString(String str) {
        return new MAC.InvalidMacException(
            "MAC address string must be 6 words of 1 or 2 hex digits " +
            "joined with 5 ':' but was " + str + ".");
    }

    /** Parses a string representing a MAC address and returns its bit representation
     * Replacement for org.midonet.packages.MAC.stringToLong
     * @throws MAC.InvalidMacException
     */
    public static long macStringToLong(String str) throws MAC.InvalidMacException {
        if (str == null || str.length() < 11)
            throw illegalMacString(str);

        long addr = 0;
        int start = 0,
            count=0;
        int length = str.length();
        // discard separators at end, to mimic [[String]].split behavior.
        while (length>0 && str.charAt(length-1)==':')
            -- length;
        do {
            int next = str.indexOf(':', start);
            if (next == -1) next = length;
            int len = next - start;
            if (len==0 || len > 2) throw illegalMacString(str);
            addr = (addr << 8) | hexToLong(str, start, next - 1);
            start = next + 1;
            count += 1;
        }
        while (start < length);
        if (count != 6) throw illegalMacString(str);
        return addr;
    }
}

