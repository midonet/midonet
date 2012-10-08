package com.midokura.mmdpctl;

import com.midokura.mmdpctl.netlink.NetlinkClient;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.packets.Net;
import com.midokura.sdn.dp.Datapath;
import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.flows.*;


import static com.midokura.sdn.dp.flows.FlowActions.output;
import static com.midokura.sdn.dp.flows.FlowKeys.*;


/**
 * Copy & paste class to insert one single flow into the system.
 * TODO Delete this.
 */
public class InsertFlows {
    private static String HEXES = "0123456789ABCDEF";

    public void run() throws Exception {
        OvsDatapathConnection connection = NetlinkClient.createDatapathConnection();
        Datapath marc_datapath = connection.datapathsGet("marc_datapath").get();
        Flow flow =
                new Flow()
                        .addKey(inPort(0))
                        .addKey(
                                ethernet(macFromString("ae:b3:77:8C:A1:48"),
                                        macFromString("33:33:00:00:00:16")))
                        .addKey(etherType(FlowKeyEtherType.Type.ETH_P_IPV6))
                        .addKey(
                                ipv6(
                                        Net.ipv6FromString("::"),
                                        Net.ipv6FromString("ff02::16"),
                                        58)
                                        .setHLimit((byte) 1))
                        .addKey(
                                icmpv6(143, 0)
                        )
                        .addAction(output(4))
                        .addAction(output(3))
                        .addAction(output(2))
                        .addAction(output(1));

        // wait for the flow to be created.
        connection.flowsCreate(marc_datapath, flow).get();

    }

    protected byte[] macFromString(String macAddress) {
        byte[] address = new byte[6];
        String[] macBytes = macAddress.split(":");
        if (macBytes.length != 6)
            throw new IllegalArgumentException(
                    "Specified MAC Address must contain 12 hex digits" +
                            " separated pairwise by :'s.");

        for (int i = 0; i < 6; ++i) {
            address[i] = (byte) (
                    (HEXES.indexOf(macBytes[i].toUpperCase().charAt(0)) << 4) |
                            HEXES.indexOf(macBytes[i].toUpperCase().charAt(1))
            );
        }

        return address;

    }
}
