package com.midokura.midolman.state;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

public class Route implements Serializable {

    public enum NextHop {
        BLACKHOLE, REJECT, PORT;
    }

    InetAddress srcNetworkAddr;
    byte srcNetworkLength;
    InetAddress dstNetworkAddr;
    byte dstNetworkLength;
    NextHop nextHop;
    UUID nextHopPort;
    InetAddress nextHopGateway;
    int weight;
    String attributes;

    protected String encodeInetAddress(InetAddress value) {
        byte[] addr = value.getAddress();
        StringBuilder builder = new StringBuilder();
        builder.append(addr[0]);
        for (int i=1; i < addr.length; i++)
            builder.append(".").append(addr[i]);
        return builder.toString();
    }

    protected static InetAddress decodeInetAddress(String str) {
        String[] parts = str.split(".");
        byte[] bytes = new byte[parts.length];
        for (int i=0; i<parts.length; i++)
            bytes[i] = Byte.parseByte(parts[i]);
        try {
            return InetAddress.getByAddress(bytes);
        }
        catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(encodeInetAddress(srcNetworkAddr)).append(",");
        sb.append(srcNetworkLength).append(",");
        sb.append(encodeInetAddress(dstNetworkAddr)).append(",");
        sb.append(dstNetworkLength).append(",");
        sb.append(nextHop.toString()).append(",");
        if (null != nextHopPort)
            sb.append(nextHopPort.toString());
        sb.append(",");
        if (null != nextHopGateway)
            sb.append(encodeInetAddress(nextHopGateway));
        sb.append(",");
        sb.append(weight).append(",");
        sb.append(attributes);
        return sb.toString();
    }

    public static Route fromString(String str) {
        String[] parts = str.split(",");
        Route rt = new Route();
        rt.srcNetworkAddr = decodeInetAddress(parts[0]);
        rt.srcNetworkLength = Byte.parseByte(parts[1]);
        rt.dstNetworkAddr = decodeInetAddress(parts[2]);
        rt.dstNetworkLength = Byte.parseByte(parts[3]);
        rt.nextHop = NextHop.valueOf(parts[4]);
        rt.nextHopPort = parts[5].isEmpty()? null : UUID.fromString(parts[5]);
        rt.nextHopGateway = parts[6].isEmpty()? 
                null : decodeInetAddress(parts[6]);
        rt.weight = Integer.parseInt(parts[7]);
        rt.attributes = parts[8];
        return rt;
    }

}
