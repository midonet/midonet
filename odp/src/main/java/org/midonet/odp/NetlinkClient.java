/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.odp;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.NetlinkSelectorProvider;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.packets.MAC;
import org.midonet.packets.Net;
import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKeyEtherType;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.SelectListener;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.TryCatchReactor;
import static org.midonet.netlink.Netlink.Protocol;
import static org.midonet.odp.flows.FlowActions.output;
import static org.midonet.odp.flows.FlowKeys.arp;
import static org.midonet.odp.flows.FlowKeys.encap;
import static org.midonet.odp.flows.FlowKeys.etherType;
import static org.midonet.odp.flows.FlowKeys.ethernet;
import static org.midonet.odp.flows.FlowKeys.inPort;
import static org.midonet.odp.flows.FlowKeys.vlan;


public class NetlinkClient {

    private static final Logger log = LoggerFactory
        .getLogger(NetlinkClient.class);

    public static OvsDatapathConnection createDatapathConnection()
        throws Exception {
        SelectorProvider provider = SelectorProvider.provider();

        if (!(provider instanceof NetlinkSelectorProvider)) {
            log.error("Invalid selector type: {}", provider.getClass());
            throw new RuntimeException();
        }

        NetlinkSelectorProvider netlinkSelector = (NetlinkSelectorProvider) provider;

        final NetlinkChannel netlinkChannel =
            netlinkSelector.openNetlinkSocketChannel(Protocol.NETLINK_GENERIC);

        log.info("Connecting");
        netlinkChannel.connect(new Netlink.Address(0));

        log.info("Creating the selector loop");
        final SelectLoop loop = new SelectLoop();
        final Reactor reactor = new TryCatchReactor("client", 1);

        log.info("Making the ovsConnection");
        final OvsDatapathConnection ovsConnection =
            OvsDatapathConnection.create(netlinkChannel, reactor);

        log.info("Setting the channel to non blocking");
        netlinkChannel.configureBlocking(false);

        log.info("Registering the channel into the selector");
        loop.register(netlinkChannel, SelectionKey.OP_READ,
                      new SelectListener() {
                          @Override
                          public void handleEvent(SelectionKey key)
                              throws IOException {
                              ovsConnection.handleReadEvent(key);
                          }
                      });

        Thread loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    log.info("Entering loop");
                    loop.doLoop();
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
            }
        });

        log.info("Starting the selector loop");
        loopThread.start();

        log.info("Initializing ovs connection");
        ovsConnection.initialize();

        while (!ovsConnection.isInitialized()) {
            Thread.sleep(TimeUnit.MILLISECONDS.toMillis(50));
        }

        return ovsConnection;
    }

    public static void main(String[] args) throws Exception {

        OvsDatapathConnection conn = createDatapathConnection();

        Datapath datapath = conn.datapathsGet("bibi").get();

        log.info("Got datapath by name: {}.", datapath);

        Flow flow =
            conn.flowsCreate(datapath, new Flow().setMatch(flowMatch())).get();

        log.info("Installed flow: {}, ", flow);

        log.info("Delete flow operation: {}", conn.flowsDelete(datapath, flow).get());

        log.info("Remaining flows: {}", conn.flowsEnumerate(datapath).get());
    }

    private static List<FlowAction<?>> flowActions() {
        return Arrays.<FlowAction<?>>asList(output(513));
    }

    private static FlowMatch flowMatch() {
        return new FlowMatch()
            .addKey(inPort(0))
            .addKey(ethernet(MAC.fromString("ae:b3:77:8c:a1:48").getAddress(),
                             MAC.fromString("33:33:00:00:00:16").getAddress()))
            .addKey(etherType(FlowKeyEtherType.Type.ETH_P_8021Q))
            .addKey(vlan(0x0111))
            .addKey(
                encap()
                    .addKey(
                        etherType(FlowKeyEtherType.Type.ETH_P_ARP))
                    .addKey(
                            arp(MAC.fromString("ae:b3:77:8d:c1:48").getAddress(),
                                    MAC.fromString("ae:b3:70:8d:c1:48").getAddress())
                                    .setOp((short) 2)
                                    .setSip(Net.convertStringAddressToInt(
                                            "192.168.100.1"))
                                    .setTip(Net.convertStringAddressToInt(
                                            "192.168.102.1"))
                    ));
    }
}
