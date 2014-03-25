/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.data;

import static junitparams.JUnitParamsRunner.*;

/**
 * Provides data for IPv4 CIDRs tests
 */
public class IPv4CidrProvider {

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
                $("1.1.1.1"),
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
}
