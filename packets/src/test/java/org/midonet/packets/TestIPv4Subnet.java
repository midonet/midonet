/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.packets;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.data.IPv4CidrProvider;

@RunWith(Enclosed.class)
public class TestIPv4Subnet {

    private static IPv4Subnet testObject;

    @RunWith(JUnitParamsRunner.class)
    public static class TestConstructorWithCidr {

        @Test
        @Parameters(source = IPv4CidrProvider.class, method="validCidrs")
        public void testValidCidrs(String input) {

            testObject = new IPv4Subnet(input);

            // toString simply re-constructs the original format
            Assert.assertEquals(input, testObject.toString());
        }

        @Test(expected = IllegalArgumentException.class)
        @Parameters(source = IPv4CidrProvider.class, method="invalidCidrs")
        public void testInvalidCidrs(String input) {

            testObject = new IPv4Subnet(input);

        }
    }
}

