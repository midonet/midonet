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
                    " at position " + i + " in string: " + s);
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

        public String toString() {
            return String.format("%08x-%04x-%04x-%04x-%012x",
                                 msb >>> 32,
                                 (msb >>> 16) & 0xffff,
                                 msb & 0xffff,
                                 lsb >>> 48,
                                 lsb & 0xffffffffffffL);
        }

        private static class ParseInfo {
            public final int dest;
            public final int shift;
            public final int maxlen;
            public final boolean end;

            ParseInfo(int d, int s, int m, boolean e) {
                dest = d;
                shift = s;
                maxlen = m;
                end = e;
            }
        }

        static final ParseInfo parser[] = {
            new ParseInfo(0, 32,  8, false),
            new ParseInfo(0, 16,  4, false),
            new ParseInfo(0,  0,  4, false),
            new ParseInfo(1, 48,  4, false),
            new ParseInfo(1,  0, 12, true)
        };

        private boolean fillFromStringImpl(String s) {
            if (s.length() < 9) return false;
            int start = 0, end;
            long bits[] = {0, 0};
            for (ParseInfo p : parser) {
                if (!p.end) {
                    end = s.indexOf('-', start);
                } else {
                    end = s.length();
                    while (end > start && s.charAt(end-1) == '-') --end;
                }
                if (end <= start || end - start > p.maxlen) return false;
                bits[p.dest] |= hexToLong(s, start, end-1) << p.shift;
                start = end + 1;
            }
            this.msb = bits[0];
            this.lsb = bits[1];
            return true;
        }

        public void fillFromString(String s) {
            if (!fillFromStringImpl(s)) {
                throw new IllegalArgumentException("Invalid UUID string: " + s);
            }
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

