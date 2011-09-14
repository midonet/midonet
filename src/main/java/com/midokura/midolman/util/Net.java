/*
 * @(#)Net        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.util;

import java.net.InetAddress;

/**
 * Net utility class.
 *
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class Net {

    /**
     * Converts InetAddress IP address to int.
     * 
     * @param address IP address in InetAddress.
     * @return  IP address in int.
     */
    public static int convertInetAddressToInt(InetAddress address) {
	byte[] rawAddr = address.getAddress();
        int num = 0;
        for (byte octet : rawAddr) {
	    num <<= 8;
	    num += octet & 0xff;
        }
        return num;
	// FIXME(jlm): Test this!
    }
 
    /**
     * Converts int IP address to string.
     * 
     * @param address IP address in int.
     * @return  IP address in String.
     */
    public static String convertIntAddressToString(int address) {
        return ((address >> 24) & 0xFF) + "."
            + ((address >> 16) & 0xFF) + "."
            + ((address >>  8) & 0xFF) + "."
            + (address & 0xFF);
    }

    /**
     * Converts string IP address to int.
     * 
     * @param address IP address in string.
     * @return  IP address in int.
     */
    public static int convertStringAddressToInt(String address) {
        String[] addrArray = address.split("\\.");
        int num = 0;
        for (int i=0;i<addrArray.length;i++) {
			// Shift one octet to the left.
			num <<= 8;
			num += (Integer.parseInt(addrArray[i]) & 0xff);
        }
        return num;       
    }

}
