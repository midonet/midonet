/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.midonet.data.IPv4CidrProvider;
import org.testng.Assert;

@RunWith(JUnitParamsRunner.class)
public class TestNetUtil {

    @Test
    @Parameters(source = IPv4CidrProvider.class, method="validCidrs")
    public void testIsValidIpv4Cidr(String input) {

        Assert.assertTrue(NetUtil.isValidIpv4Cidr(input));
    }

    @Test
    @Parameters(source = IPv4CidrProvider.class, method="invalidCidrs")
    public void testIsValidIpv4CidrNegative(String input) {

        Assert.assertFalse(NetUtil.isValidIpv4Cidr(input));
    }

    @Test
    @Parameters(source = IPv4CidrProvider.class, method="validCidrs")
    public void testGetAddressAndPrefixLen(String input) {

        String[] expected = input.split("/");
        Pair<String, Integer> actual = NetUtil.getAddressAndPrefixLen(input);

        Assert.assertEquals(expected[0], actual.getLeft());
        Assert.assertEquals(expected[1], actual.getRight().toString());
    }

    @Test(expected = IllegalArgumentException.class)
    @Parameters(source = IPv4CidrProvider.class, method="invalidCidrs")
    public void testGetAddressAndPrefixLenNegative(String input) {

        NetUtil.getAddressAndPrefixLen(input);
    }
}
