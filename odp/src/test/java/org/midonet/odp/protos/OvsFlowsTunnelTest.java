/*
* Copyright 2013 Midokura
*/
package org.midonet.odp.protos;

import java.util.Arrays;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.midonet.odp.Datapath;
import org.midonet.odp.DatapathClient;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.flows.*;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.odp.flows.FlowKeys.*;
import static org.midonet.odp.flows.FlowActions.setKey;

/*
 * Note: This test depends on OVS kmod version >= 1.9.0
 */
@Ignore
public class OvsFlowsTunnelTest {

    private static final Logger log = LoggerFactory
        .getLogger(OvsFlowsTunnelTest.class);

    private OvsDatapathConnection connection;
    private static final String datapathName = "fttest";

    @Before
    public void setUp() {
        try {
            connection = DatapathClient.createConnection();
        } catch (Exception e) {
            log.error("Could not connect to netlink: " + e.getMessage());
        }
    }

    @Test
    public void testFlowBasedTunnelSetAction() {
        // first create datapath
        Future<Datapath> dpFuture;
        Datapath datapath = null;
        try {
            dpFuture = connection.datapathsCreate(datapathName);
            datapath = dpFuture.get();
        } catch (Exception e) {
            log.error("Error creating datapath " + datapathName +
                      " " + e.getMessage());
        }

        IPv4Subnet srcIp = new IPv4Subnet("10.10.10.10", 24);
        IPv4Subnet dstIp = new IPv4Subnet("10.10.11.10", 24);
        IPv4Subnet tunnelSrcIp = new IPv4Subnet("10.11.12.13", 24);
        IPv4Subnet tunnelDstIp = new IPv4Subnet("10.11.12.14", 24);
        MAC srcMac = MAC.fromString("aa:33:44:55:66:77");
        MAC dstMac = MAC.fromString("aa:22:44:66:88:bb");
        FlowKeyEthernet ethernetKey = ethernet(srcMac.getAddress(),
                                               dstMac.getAddress());
        FlowKeyEtherType etherTypeKey = etherType((short) 0x0800);
        FlowKeyIPv4 ipv4Key = ipv4(srcIp.getAddress(), dstIp.getAddress(),
                IpProtocol.TCP);
        FlowMatch matchKey = new FlowMatch().addKey(ethernetKey)
                         .addKey(etherTypeKey)
                         .addKey(ipv4Key);
        FlowKeyTunnel ipv4TunnelKey = tunnel(10, tunnelSrcIp.getAddress().addr(),
                                                 tunnelDstIp.getAddress().addr());
        FlowActionSetKey setKeyAction = setKey(ipv4TunnelKey);
        Flow downloadFlow = new Flow()
             .setMatch(matchKey)
             .setActions(Arrays.<FlowAction<?>>asList(setKeyAction));
        Future<Flow> flowFuture =
            connection.flowsCreate(datapath, downloadFlow);

        try {
            if (flowFuture.get() == null) {
                log.error("Flow create failed");
            }
        } catch (Exception e) {
            log.error("Error retrieving flow from flowCreate " + e.getMessage());
        }

        Future<Flow> retrievedFlow = connection.flowsGet(datapath, matchKey);
        try {
            if (retrievedFlow.get() == null) {
                log.error("Flow add failed");
            }
        } catch (Exception e) {
            log.error("Error retrieving flow " + e.getMessage());
        }

        try {
            Future<Datapath> deleteDatapath =
                connection.datapathsDelete(datapathName);

            if (deleteDatapath.get() == null) {
                log.error("Datapath delete failed");
            }
        } catch (Exception e) {
            log.error("Error deleting datapth " + e.getMessage());
        }
    }
}
