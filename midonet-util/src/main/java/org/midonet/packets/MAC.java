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

package org.midonet.packets;

import java.util.Random;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import org.midonet.util.collection.WeakObjectPool;

/** Representation of a mac address. Utility class for a Ethernet-type Media
 *  Access Control address ("Hardware" or "data link" or "link layer" address).
 *
 *  Conversion functions taking or returning long values assume the following
 *  encoding rules:
 *      1) the highest two bytes of the long value are ignored or set to 0;
 *      2) the ordering of bytes in the long from MSB to LSB follows the mac
 *          address representation as a string reading from left to right.
 */
public class MAC {
    private static WeakObjectPool<MAC> INSTANCE_POOL = new WeakObjectPool<>();

    private static final Random rand = new Random();

    public static final long MAC_MASK = 0x0000FFFFFFFFFFFFL;
    public static final long MAC_NIC_MASK = 0x0000000000FFFFFFL;
    // Midokura's Organization Unique Identifier as follows:
    //   AC-CA-BA   (hex)       Midokura Co., Ltd.
    //   ACCABA     (base 16)   Midokura Co., Ltd.
    // Refer to the following OUI list:
    //   http://standards.ieee.org/develop/regauth/oui/oui.txt
    public static final long MIDOKURA_OUI_MASK = 0x0000ACCABA000000L;
    public static final long MULTICAST_BIT = 0x1L << 40;

    public final static String regex =
        "^((([0-9]|[a-f]|[A-F]){2}):){5}([0-9]|[a-f]|[A-F]){2}$";

    private final long addr;
    private String sAddr = null; // not final to allow lazy init

    public static class InvalidMacException extends IllegalArgumentException {

        private static final long serialVersionUID = 1961257300022073971L;

        public InvalidMacException(String msg) {
            super(msg);
        }
    }

    public static class InvalidMacMaskException
            extends IllegalArgumentException {
        private static final long serialVersionUID = -3084485005471854912L;

        public InvalidMacMaskException(String msg) {
            super(msg);
        }

        public InvalidMacMaskException(String msg, Exception cause) {
            super(msg, cause);
        }
    }

    public MAC(long address) {
        addr = MAC_MASK & address;
    }

    public MAC(byte[] rhs) {
        addr = bytesToLong(rhs);
    }

    public byte[] getAddress() {
        return longToBytes(addr);
    }

    public long asLong() {
        return addr;
    }

    @JsonCreator
    public static MAC fromString(String str) {
        MAC mac = new MAC(MAC.stringToLong(str)).intern();
        mac.sAddr = str;
        return mac;
    }

    public static MAC fromAddress(byte[] rhs) {
        return new MAC(rhs).intern();
    }

    public MAC intern() {
        return INSTANCE_POOL.sharedRef(this);
    }

    public static MAC random() {
        return new MAC(rand.nextLong() &
                MAC_NIC_MASK | MIDOKURA_OUI_MASK & ~MULTICAST_BIT);
    }

    public boolean unicast() {
        return 0 == (addr & MULTICAST_BIT);
    }

    public boolean mcast() {
        return !unicast();
    }

    @JsonValue
    @Override
    public String toString() {
        if (this.sAddr == null) {
            this.sAddr = MAC.longToString(addr);
        }
        return this.sAddr;
    }

    @Override
    public boolean equals(Object rhs) {
        if (this == rhs)
            return true;
        if (!(rhs instanceof MAC))
            return false;
        return this.addr == ((MAC)rhs).addr;
    }

    @Override
    public int hashCode() {
        return (int) (addr ^ (addr >>> 32));
    }

    /**
     * Returns true if this and that are equal, considering only the
     * bits in the same position as the one bits in mask, i.e.
     * (this & mask) == (that & mask).
     *
     * The 16 most significant bits of mask have no effect, since MAC
     * addresses have only 48 bits.
     */
    public boolean equalsWithMask(MAC that, long mask) {
        return that != null && (this.addr & mask) == (that.addr & mask);
    }

    private static InvalidMacException illegalMacString(String str) {
        return new InvalidMacException(
            "MAC address string must be 6 words of 1 or 2 hex digits " +
                "joined with 5 ':' but was " + str + ".");
    }

    public static long stringToLong(String str)
            throws InvalidMacException {
        if (str == null)
            throw illegalMacString(str);
        String[] macBytes = str.split(":");
        if (macBytes.length != 6)
            throw illegalMacString(str);
        long addr = 0;
        try {
            for (String s : macBytes) {
                if (s.length() > 2)
                    throw illegalMacString(str);
                addr = (addr << 8) + (0xFFL & Integer.parseInt(s, 16));
            }
        } catch(NumberFormatException ex) {
            throw illegalMacString(str);
        }
        return addr;
    }

    public static byte[] stringToBytes(String str)
            throws InvalidMacException {
        if (str == null)
            throw illegalMacString(str);
        String[] macBytes = str.split(":");
        if (macBytes.length != 6)
            throw illegalMacString(str);

        byte[] addr = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                String s = macBytes[i];
                if (s.length() > 2)
                    throw illegalMacString(str);
                addr[i] = (byte) Integer.parseInt(s, 16);
            }
        } catch(NumberFormatException ex) {
            throw illegalMacString(str);
        }
        return addr;
    }

    public static String longToString(long addr) {
        return String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            (addr & 0xff0000000000L) >> 40,
            (addr & 0x00ff00000000L) >> 32,
            (addr & 0x0000ff000000L) >> 24,
            (addr & 0x000000ff0000L) >> 16,
            (addr & 0x00000000ff00L) >> 8,
            (addr & 0x0000000000ffL)
        );
    }

    public static byte[] longToBytes(long addr) {
        byte[] bytesAddr = new byte[6];
        for (int i = 5; i >= 0; i--) {
            bytesAddr[i] = (byte)(addr & 0xffL);
            addr = addr >> 8;
        }
        return bytesAddr;
    }

    private static InvalidMacException illegalMacBytes() {
        return new InvalidMacException(
            "Byte array representing a MAC address must have length 6 exactly.");
    }

    public static long bytesToLong(byte[] bytesAddr)
            throws InvalidMacException {
        if (bytesAddr == null || bytesAddr.length != 6)
             throw illegalMacBytes();
        long addr = 0;
        for (int i = 0; i < 6; i++) {
            addr = (addr << 8) + (bytesAddr[i] & 0xffL);
        }
        return addr;
    }

    public static String bytesToString(byte[] address)
            throws InvalidMacException {
        if (address == null || address.length != 6)
            throw illegalMacBytes();
        return String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            address[0],
            address[1],
            address[2],
            address[3],
            address[4],
            address[5]
        );
    }

    public static long parseMask(String maskStr)
            throws InvalidMacMaskException {
        String error = "MAC mask must consist of three sets of four " +
                "hexadecimal digits separated by periods, " +
                "e.g. \"ffff.ff00.0000\".";
        String[] words = maskStr.split("\\.");
        if (words.length != 3)
            throw new InvalidMacMaskException(error);

        long mask = 0L;
        for (String word : words) {
            if (word.length() != 4)
                throw new InvalidMacMaskException(error);
            try {
                mask = (mask << 16) | Integer.parseInt(word, 16);
            } catch (NumberFormatException ex) {
                throw new InvalidMacMaskException(error, ex);
            }
        }

        return mask;
    }

    public static String maskToString(long mask) {
        return String.format("%04x.%04x.%04x",
                (mask >> 32) & 0xffff,
                (mask >> 16) & 0xffff,
                mask & 0xffff);
    }
}
