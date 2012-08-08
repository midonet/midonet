/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.netlink;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.util.Net;
import com.midokura.netlink.Netlink;
import com.midokura.netlink.NetlinkChannel;
import com.midokura.netlink.NetlinkSelectorProvider;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.packets.MAC;
import com.midokura.sdn.dp.Datapath;
import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowAction;
import com.midokura.sdn.dp.flows.FlowKeyEtherType;
import com.midokura.util.eventloop.SelectListener;
import com.midokura.util.eventloop.SelectLoop;
import static com.midokura.netlink.Netlink.Protocol;
import static com.midokura.sdn.dp.flows.FlowActions.output;
import static com.midokura.sdn.dp.flows.FlowKeys.arp;
import static com.midokura.sdn.dp.flows.FlowKeys.encap;
import static com.midokura.sdn.dp.flows.FlowKeys.etherType;
import static com.midokura.sdn.dp.flows.FlowKeys.ethernet;
import static com.midokura.sdn.dp.flows.FlowKeys.inPort;
import static com.midokura.sdn.dp.flows.FlowKeys.vlan;

public class Client {

    private static final Logger log = LoggerFactory
        .getLogger(Client.class);

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
        final SelectLoop loop = new SelectLoop(
            Executors.newScheduledThreadPool(1));

        log.info("Making the ovsConnection");
        final OvsDatapathConnection ovsConnection =
            OvsDatapathConnection.create(netlinkChannel, loop);

        log.info("Setting the channel to non blocking");
        netlinkChannel.configureBlocking(false);

        log.info("Registering the channel into the selector");
        loop.register(netlinkChannel, SelectionKey.OP_READ,
                      new SelectListener() {
                          @Override
                          public void handleEvent(SelectionKey key)
                              throws IOException {
                              ovsConnection.handleEvent(key);
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

        return ovsConnection;
    }

    public static void main(String[] args) throws Exception {

        OvsDatapathConnection conn = createDatapathConnection();

        log.info("Initializing ovs connection");
        conn.initialize();

        while (!conn.isInitialized()) {
            Thread.sleep(TimeUnit.MILLISECONDS.toMillis(50));
        }

        Datapath datapath = conn.datapathsGet("bibi").get();

        log.info("Got datapath by name: {}.", datapath);

//        conn.datapathsSetNotificationHandler(datapath, new AbstractNetlinkConnection.Callback<Packet>() {
//            @Override
//            public void onSuccess(Packet data) {
//                log.info("Packet-in received: {}", data);
//            }
//        }).get();


        log.info("Installed flow: {}, ",
                 conn.flowsCreate(datapath, new Flow().setMatch(flowMatch()))
                     .get());

        Flow retrievedFlow = conn.flowsGet(datapath, flowMatch()).get();

        log.info("Retrieved flow: {}", retrievedFlow);


        retrievedFlow.setActions(flowActions());

        Flow updatedFlow = new Flow()
            .setMatch(flowMatch())
            .setActions(flowActions());

        log.info("Flow that was set: {}",
                 conn.flowsSet(datapath, updatedFlow).get());
        // multi containing the ports data
//        fireReply();

//        Flow flow =
//            new Flow()
//                .addKey(FlowKeys.inPort(0))
//                .addAction(
//                    FlowActions.output(0));
//
//        Port port = conn.portsGet(0, datapath).get();
//        log.info("Old port {}", port);
//        port = conn.portsSet(port, datapath).get();
//        log.info("New port {}", port);
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
