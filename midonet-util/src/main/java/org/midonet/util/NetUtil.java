/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util;

import org.apache.commons.lang3.tuple.Pair;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class that contains network related utility methods
 */
public class NetUtil {

    private NetUtil() {}

    /**
     * Regex pattern representing IPv4 CIDR
     */
    public static String IPV4_CIDR_PATTERN =
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
                    "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])" +
                    "(\\/(\\d|[1-2]\\d|3[0-2]))$";

    private static Pattern ipv4CidrPattern =
            Pattern.compile(IPV4_CIDR_PATTERN);

    /**
     * Checks whether CIDR is in the correct format.  The expected format
     * is n.n.n.n/m where n.n.n.n is a valid quad-dotted IPv4 address and m is
     * a prefix length value in [0, 32].  False is returned if cidr is null.
     *
     * @param cidr CIDR to validate
     * @return True if CIDR is valid
     */
    public static boolean isValidIpv4Cidr(String cidr) {

        if (cidr == null) {
            return false;
        }

        Matcher matcher = ipv4CidrPattern.matcher(cidr);
        return matcher.matches();
    }

    /**
     * Extracts the IP address and the prefix length from a given IPv4 CIDR.
     * CIDR is expected to be in the format of n.n.n.n/m where n.n.n.n is a
     * valid quad-dotted IPv4 address and m is a prefix length value in
     * [0, 32].
     *
     * IllegalArgumentException is thrown if the provided cidr is invalid,
     * including null.
     *
     * @param cidr CIDR to get the address and prefix length from
     * @return Pair of the address and prefix length
     */
    public static Pair<String, Integer> getAddressAndPrefixLen(String cidr) {

        if (!isValidIpv4Cidr(cidr)) {
            throw new IllegalArgumentException("cidr is not valid");
        }

        String[] parts = cidr.split("/");
        assert parts.length == 2;     // Sanity check

        return Pair.of(parts[0], Integer.parseInt(parts[1]));
    }
}
