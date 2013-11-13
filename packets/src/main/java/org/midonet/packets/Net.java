/*
 * @(#)Net        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package org.midonet.packets;

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
     * Converts int array ipv4 to String
     *
     * @param ipv6 ipv6 address as int array
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

    /**
     * Convert string ipv6 to an four int array
     * @param ip the ip representation as a string
     *
     * @return the converted value
     */
    public static int[] ipv6FromString(String ip) {
        int []address = new int[4];

        try {
            ByteBuffer
                .wrap(Inet6Address.getByName(ip).getAddress())
                .asIntBuffer()
                .get(address);
        } catch (UnknownHostException e) {
            //
        }

        return address;
    }

}
