/*
 * @(#)Net        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Net utility class.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class Net {

    /**
     * Converts InetAddress IP address to int.
     *
     * @param address
     *            IP address in InetAddress.
     * @return IP address in int.
     */
    public static int convertInetAddressToInt(InetAddress address) {
        byte[] rawAddr = address.getAddress();
        int num = 0;
        for (byte octet : rawAddr) {
            num <<= 8;
            num += octet & 0xff;
        }
        return num;
    }

    public static InetAddress convertIntToInetAddress(int intAddress) {
        byte[] byteAddress = {
                (byte) (intAddress >> 24),
                (byte) ((intAddress >> 16) & 0xff),
                (byte) ((intAddress >> 8) & 0xff),
                (byte) (intAddress & 0xff) };
        try {
            return InetAddress.getByAddress(byteAddress);
        } catch (UnknownHostException e) {
            throw new RuntimeException("getByAddress on a raw address threw "
                    + "an UnknownHostException", e);
        }
    }

    /**
     * Converts int IP address to string.
     *
     * @param address
     *            IP address in int.
     * @return IP address in String.
     */
    public static String convertIntAddressToString(int address) {
        return ((address >> 24) & 0xFF) + "." +
               ((address >> 16) & 0xFF) + "." +
               ((address >> 8) & 0xFF) + "." +
               (address & 0xFF);
    }

    /**
     * Converts string IP address to int.
     *
     * @param address
     *            IP address in string.
     * @return IP address in int.
     */
    public static int convertStringAddressToInt(String address) {
        String[] addrArray = address.split("\\.");
        int num = 0;
        if (addrArray.length != 4)
            throw new IllegalArgumentException("Specified IPv4 address must" +
                    "contain 4 sets of numerical digits separated by periods");
        for (int i = 0; i < addrArray.length; i++) {
            int addr = Integer.parseInt(addrArray[i]);
            if (addr < 0 || addr > 255) {
                throw new IllegalArgumentException("Specified IPv4 address must" +
                        "contain 4 sets of numerical digits separated by periods");
            }
            // Shift one octet to the left.
            num <<= 8;
            num += addr & 0xff;
        }
        return num;
    }

    /**
     * Converts byte array MAC address to String.
     *
     * @param address
     *            MAC address as byte array.
     * @return MAC address as String.
     */
    public static String convertByteMacToString(byte[] address) {
        assert address.length == 6;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02x", address[0]));
        for (int i=1; i<address.length; i++)
            sb.append(":").append(String.format("%02x", address[i]));
        return sb.toString();
        // TODO: Test this.
    }

    /**
     * Converts int array ipv4 to String
     *
     * @param address ipv6 address as int array
     *
     * @return IPv6 address as String
     */
    public static String convertIPv6BytesToString(int[] ipv6) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(16);
        IntBuffer intBuffer = byteBuffer.asIntBuffer();
        intBuffer.put(ipv6[0]);
        intBuffer.put(ipv6[1]);
        intBuffer.put(ipv6[2]);
        intBuffer.put(ipv6[3]);

        try {
            return Inet6Address.getByAddress(byteBuffer.array()).getHostAddress();
        } catch (UnknownHostException e) {
            return "";
        }
    }
}
