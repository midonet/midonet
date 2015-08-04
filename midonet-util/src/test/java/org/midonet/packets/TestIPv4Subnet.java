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

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junitparams.JUnitParamsRunner.*;

@RunWith(JUnitParamsRunner.class)
public class TestIPv4Subnet {
    @Test
    @Parameters(source = TestIPv4Subnet.class, method="validCidrs")
    public void testValidCidrs(String input) {
        String[] expected = input.split("/");
        IPv4Subnet testObject = IPv4Subnet.fromCidr(input);

        // toString simply re-constructs the original format
        Assert.assertEquals(input, testObject.toString());
        Assert.assertEquals(expected[0], testObject.getAddress().toString());
        Assert.assertEquals(expected[1],
                Integer.toString(testObject.getPrefixLen()));
    }

    @Test(expected = IllegalArgumentException.class)
    @Parameters(source = TestIPv4Subnet.class, method="invalidCidrs")
    public void testInvalidCidrs(String input) {
        IPv4Subnet.fromCidr(input);
    }

    @Test
    @Parameters(source = TestIPv4Subnet.class, method="validCidrs")
    public void testIsValidIpv4Cidr(String input) {
        Assert.assertTrue(IPv4Subnet.isValidIpv4Cidr(input));
    }

    @Test
    @Parameters(source = TestIPv4Subnet.class, method="invalidCidrs")
    public void testIsValidIpv4CidrNegative(String input) {
        Assert.assertFalse(IPv4Subnet.isValidIpv4Cidr(input));
    }

    @Test(expected = IllegalArgumentException.class)
    @Parameters(source = TestIPv4Subnet.class, method="invalidCidrs")
    public void testGetAddressAndPrefixLenNegative(String input) {
        IPv4Subnet.fromCidr(input);
    }

    @Test
    @Parameters(source = TestIPv4Subnet.class, method="prefixLenToBytes")
    public void testPrefixLenToBytes(int prefixLen, byte[] expected) {
        Assert.assertArrayEquals(
            expected, IPv4Subnet.prefixLenToBytes(prefixLen));
    }

    @Test
    public void testDefaultMask() {
        IPv4Subnet subnet = IPv4Subnet.fromCidr("1.1.1.1");
        Assert.assertEquals("1.1.1.1/32", subnet.toString());
    }

    public static Object[] validCidrs() {
        return $(
                $("0.0.0.0/0"),
                $("0.0.0.0/16"),
                $("0.0.0.0/32"),
                $("10.10.10.10/0"),
                $("10.10.10.10/16"),
                $("10.10.10.10/32"),
                $("255.255.255.255/0"),
                $("255.255.255.255/16"),
                $("255.255.255.255/32")
        );
    }

    public static Object[] invalidCidrs() {
        return $(
                $(""),
                $("foo"),

                // Invalid delim
                $("1.1.1.1_32"),
                $("1.1.1.1 32"),

                // Bad prefix len
                $("1.1.1.1/"),
                $("1.1.1.1/foo"),
                $("1.1.1.1/-1"),
                $("1.1.1.1/33"),

                // Bad address format
                $("/32"),
                $("1.1.1/32"),
                $("1.1.1.1.1/32"),
                $("-1.1.1.1/0"),
                $("1.-1.1.1/0"),
                $("1.1.-1.1/0"),
                $("1.1.1.-1/0"),
                $("256.255.255.255/0"),
                $("255.256.255.255/0"),
                $("255.255.256.255/0"),
                $("255.255.255.256/0")
        );
    }

    @SuppressWarnings("uncheck")
    public static Object[] prefixLenToBytes() {
        return $($(32, new byte[] {
                        (byte) 255, (byte) 255, (byte) 255, (byte) 255 }),
                $(24, new byte[] {
                        (byte) 255, (byte) 255, (byte) 255, (byte) 0 }),
                $(16, new byte[] {
                        (byte) 255, (byte) 255, (byte) 0, (byte) 0 }),
                $(8, new byte[] {
                        (byte) 255, (byte) 0, (byte) 0, (byte) 0 }),
                $(23, new byte[] {
                        (byte) 255, (byte) 255, (byte) 254, (byte) 0 }),
                $(17, new byte[] {
                        (byte) 255, (byte) 255, (byte) 128, (byte) 0}),
                $(3, new byte[] {
                        (byte) 224, (byte) 0, (byte) 0, (byte) 0 }));
    }
}
