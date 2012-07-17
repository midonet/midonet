/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.netlink;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.util.netlink.AbstractNetlinkConnection;
import com.midokura.util.netlink.Netlink;
import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.netlink.NetlinkSelectorProvider;
import com.midokura.util.netlink.dp.Datapath;
import com.midokura.util.netlink.dp.Flow;
import com.midokura.util.netlink.dp.Packet;
import com.midokura.util.netlink.dp.flows.FlowKeyEtherType;
import com.midokura.util.netlink.dp.flows.IpProtocol;
import com.midokura.util.netlink.protos.OvsDatapathConnection;
import com.midokura.util.reactor.Reactor;
import static com.midokura.util.netlink.Netlink.Protocol;
import static com.midokura.util.netlink.dp.flows.FlowActions.userspace;
import static com.midokura.util.netlink.dp.flows.FlowKeys.etherType;
import static com.midokura.util.netlink.dp.flows.FlowKeys.ethernet;
import static com.midokura.util.netlink.dp.flows.FlowKeys.icmp;
import static com.midokura.util.netlink.dp.flows.FlowKeys.inPort;
import static com.midokura.util.netlink.dp.flows.FlowKeys.ipv4;

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
            OvsDatapathConnection.create(netlinkChannel, new Reactor() {
                @Override
                public long currentTimeMillis() {
                    return loop.currentTimeMillis();
                }

                @Override
                public <V> Future<V> submit(final Callable<V> work) {
                    //noinspection unchecked
                    return (Future<V>) loop.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                work.call();
                            } catch (Exception e) {
                                log.error("Exception");
                            }
                        }
                    });
                }

                @Override
                public <V> ScheduledFuture<V> schedule(long delay, TimeUnit unit, final Callable<V> work) {
                    //noinspection unchecked
                    return (ScheduledFuture<V>) loop.schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                work.call();
                            } catch (Exception e) {
                                log.error("Exception");
                            }
                        }
                    }, delay, unit);
                }
            });

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

        conn.datapathsSetNotificationHandler(datapath, new AbstractNetlinkConnection.Callback<Packet>() {
            @Override
            public void onSuccess(Packet data) {
                log.info("Packet-in received: {}", data);
            }
        }).get();

        log.info("Got datapath by name: {}.", datapath);

        Flow flow =
            new Flow()
                .addKey(inPort(0))
                .addKey(ethernet(MAC.fromString("ae:b3:77:8c:a1:48").getAddress(),
                                 MAC.fromString("33:33:00:00:00:16").getAddress()))
                .addKey(etherType(FlowKeyEtherType.Type.ETH_P_IP))
                .addKey(
                    ipv4(
                        IPv4.toIPv4Address("192.168.100.1"),
                        IPv4.toIPv4Address("192.168.100.2"),
                        IpProtocol.ICMP)
                        )
                .addKey(icmp(143, 0))
                .addAction(userspace().setUserData(234l));

        log.info("Installed flow: {}, ", conn.flowsCreate(datapath, flow).get());

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
}
