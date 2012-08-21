/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.sdn.dp.flows.FlowKeyEtherType;
import com.midokura.sdn.dp.flows.IpProtocol;
import static com.midokura.sdn.dp.flows.FlowKeys.etherType;
import static com.midokura.sdn.dp.flows.FlowKeys.ethernet;
import static com.midokura.sdn.dp.flows.FlowKeys.ipv4;
import static com.midokura.sdn.dp.flows.FlowKeys.tcp;

public class FlowMatches {

    public static FlowMatch tcpFlow(String macSrc, String macDst,
                                    String ipSrc, String ipDst,
                                    int portSrc, int portDst) {
        return
            new FlowMatch()
                .addKey(
                    ethernet(
                        MAC.fromString(macSrc).getAddress(),
                        MAC.fromString(macDst).getAddress()))
                .addKey(etherType(FlowKeyEtherType.Type.ETH_P_IP))
                .addKey(
                    ipv4(
                        IntIPv4.fromString(ipSrc).addressAsInt(),
                        IntIPv4.fromString(ipDst).addressAsInt(),
                        IpProtocol.TCP))
                .addKey(tcp(portSrc, portDst));
    }
}
