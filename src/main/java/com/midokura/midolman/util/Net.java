/*
 * @(#)Net        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.util;

/**
 * Net utility class.
 *
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class Net {
    
    /**
     * Converts int IP address to string.
     * 
     * @param address IP address in int.
     * @return  IP address in String.
     */
    public static String convertAddressToString(int address) {
        return ((address >> 24 ) & 0xFF) + "."
            + ((address >> 16 ) & 0xFF) + "."
            + ((address >>  8 ) & 0xFF) + "."
            + ( address & 0xFF);
    }

    /**
     * Converts string IP address to int.
     * 
     * @param address IP address in string.
     * @return  IP address in int.
     */
    public static int convertAddressToInt(String address) {
        String[] addrArray = address.split("\\.");
        int num = 0;
        for (int i=0;i<addrArray.length;i++) {
            int power = 3-i;
            num += ((Integer.parseInt(addrArray[i])%256 
                                        * Math.pow(256,power)));
        }
        return num;       
    }

}
