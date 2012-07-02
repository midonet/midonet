/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.netlink;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Executors;

import com.midokura.midolman.eventloop.SelectLoop;

public class TestDpif {

	final static byte OVS_DATAPATH_VERSION = 1;

	final static byte OVS_DP_CMD_NEW = 1;
	final static byte OVS_DP_CMD_DEL = 2;

	final static short OVS_DP_ATTR_NAME = 1;
	final static short OVS_DP_ATTR_UPCALL_PID = 2;
	final static short OVS_DP_ATTR_STATS = 3;

	public static void main(String[] args) throws Exception {
        Selector selector = Selector.open();

//        String host = "netlink://localhost:xxx";

//        InetSocketAddress socketAddress = new InetSocketAddress(host, 80);

        // NetlinkChannelImpl2 netlinkChannel = NetlinkChannelImpl2.open();
        // return SelectorProvider.provider().openNetlinkChannel();

//        SelectorProvider sp = SelectorProvider.provider();
//
//        SocketChannel channel = SocketChannel.open();
//        channel.configureBlocking(false);
//        channel.connect(socketAddress);
//
//
//        Selector selector = Selector.open();
//
//        channel.register(selector,
//                         SelectionKey.OP_CONNECT | SelectionKey.OP_READ);
//
//
//        ServerSocketChannel server1 = ServerSocketChannel.open();
//        server1.configureBlocking(false);
//        server1.socket().bind(new InetSocketAddress(80));
//        server1.register(selector, OP_ACCEPT);
//
//        ServerSocketChannel server2 = ServerSocketChannel.open();
//        server2.configureBlocking(false);
//        server2.socket().bind(new InetSocketAddress(81));
//        server2.register(selector, OP_ACCEPT);

        SelectorProvider sp = SelectorProvider.provider();

		final SelectLoop loop = new SelectLoop(Executors.newScheduledThreadPool(1));

		GenericNetlinkChannel gnc = new GenericNetlinkChannel(sp, 0);

		gnc.getNetlinkChannel().configureBlocking(false);
		loop.register(gnc.getNetlinkChannel(), SelectionKey.OP_READ, gnc);

		Thread loopThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					loop.doLoop();
				} catch (IOException e) {
					e.printStackTrace();
					System.exit(-1);
				}
			}
		});

		loopThread.start();

		OvsDpif dpif = new OvsDpif(gnc);

//		Set<String> dps = dpif.enumerateDps();
//		System.out.println("dps = " + dps);

		int dmdId = dpif.getDp("dmd");

		dpif.addPort(
				dmdId,
				OvsDpif.OVS_VPORT_TYPE_INTERNAL,
				"dmd1");
	}

}
