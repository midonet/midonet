/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.netlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;

public class OvsDpif {

	final static byte OVS_DATAPATH_VERSION = 1;

	final static byte OVS_DP_CMD_NEW = 1;
	final static byte OVS_DP_CMD_DEL = 2;
	final static byte OVS_DP_CMD_GET = 3;
	final static byte OVS_DP_CMD_SET = 4;

	final static short OVS_DP_ATTR_NAME = 1;
	final static short OVS_DP_ATTR_UPCALL_PID = 2;
	final static short OVS_DP_ATTR_STATS = 3;

	final static byte OVS_VPORT_VERSION = 1;

	final static byte OVS_VPORT_CMD_NEW = 1;
	final static byte OVS_VPORT_CMD_DEL = 2;
	final static byte OVS_VPORT_CMD_GET = 3;
	final static byte OVS_VPORT_CMD_SET = 4;

	final static int OVS_VPORT_TYPE_NETDEV = 1; /* network device */
	final static int OVS_VPORT_TYPE_INTERNAL = 2; /* network device implemented by datapath */
	final static int OVS_VPORT_TYPE_PATCH = 100; /* virtual tunnel connecting two vports */
	final static int OVS_VPORT_TYPE_GRE = 4;      /* GRE tunnel */
	final static int OVS_VPORT_TYPE_CAPWAP = 5;

	final static short OVS_VPORT_ATTR_PORT_NO = 1;	/* u32 port number within datapath */
	final static short OVS_VPORT_ATTR_TYPE = 2;	/* u32 OVS_VPORT_TYPE_* constant. */
	final static short OVS_VPORT_ATTR_NAME = 3;	/* string name, up to IFNAMSIZ bytes long */
	final static short OVS_VPORT_ATTR_OPTIONS = 4; /* nested attributes, varies by vport type */
	final static short OVS_VPORT_ATTR_UPCALL_PID = 5; /* u32 Netlink PID to receive upcalls */
	final static short OVS_VPORT_ATTR_STATS = 6;	/* struct ovs_vport_stats */
	final static short OVS_VPORT_ATTR_ADDRESS = 100; /* hardware address */

	GenericNetlinkChannel gnc;

	short ovsDpFamily;
	short ovsVportFamily;
	short ovsFlowFamily;
	short ovsPacketFamily;

	int ovsVportMulticastGroup;

	OvsDpif(GenericNetlinkChannel gnc) throws Exception {
		this.gnc = gnc;

		ovsDpFamily = gnc.getFamilyId("ovs_datapath");
		ovsVportFamily = gnc.getFamilyId("ovs_vport");
		ovsFlowFamily = gnc.getFamilyId("ovs_flow");
		ovsPacketFamily = gnc.getFamilyId("ovs_packet");

		ovsVportMulticastGroup = gnc.getMulticastGroup("ovs_vport", "ovs_vport");

		// TODO: create a netlink socket and subscribe to ovs_vport group
	}

	Set<String> enumerateDps() throws Exception {
		Set<String> dps = new HashSet<String>();

		ByteBuffer buf = ByteBuffer.allocateDirect(64);
		buf.order(ByteOrder.nativeOrder());

		// put in an int for the ovs_header->dp_index
		buf.putInt(0);

		buf.flip();

		Future<BlockingQueue<ByteBuffer>> or =
				gnc.makeRequest(
						ovsDpFamily,
						(short) (GenericNetlinkChannel.NLM_F_REQUEST | GenericNetlinkChannel.NLM_F_ECHO | GenericNetlinkChannel.NLM_F_DUMP),
						OVS_DP_CMD_GET,
						OVS_DATAPATH_VERSION,
						buf);

		// read result
		for (ByteBuffer b : or.get()) {
			NetlinkMessage nm = new NetlinkMessage(b);

			int dpIndex = b.getInt();

			dps.add(nm.findStringAttr(OVS_DP_ATTR_NAME));
		}

		return dps;
	}

	int getDp(String name) throws Exception {
		ByteBuffer buf = ByteBuffer.allocateDirect(64);
		buf.order(ByteOrder.nativeOrder());

		// put in an int for the ovs_header->dp_index
		buf.putInt(0);

		NetlinkMessage req = new NetlinkMessage(buf);

		// put desired name
		req.putStringAttr(OVS_DP_ATTR_NAME, name);

		buf.flip();

		Future<BlockingQueue<ByteBuffer>> or =
				gnc.makeRequest(
						ovsDpFamily,
						(short) (GenericNetlinkChannel.NLM_F_REQUEST | GenericNetlinkChannel.NLM_F_ECHO | GenericNetlinkChannel.NLM_F_DUMP),
						OVS_DP_CMD_GET,
						OVS_DATAPATH_VERSION,
						buf);

		ByteBuffer res = or.get().take();

		int dpIfIndex = res.getInt();

		System.out.println("dpIfIndex = " + dpIfIndex);

		NetlinkMessage resNl = new NetlinkMessage(res);

		String dpName = resNl.findStringAttr(OVS_DP_ATTR_NAME);

		System.out.println("dpName = " + dpName);

		return dpIfIndex;
	}

	int createDp(String name) throws Exception {
		ByteBuffer buf = ByteBuffer.allocateDirect(64);
		buf.order(ByteOrder.nativeOrder());

		NetlinkMessage req = new NetlinkMessage(buf);

		// put in an int for the ovs_header->dp_index
		buf.putInt(0);

		// put desired name
		req.putStringAttr(OVS_DP_ATTR_NAME, name);

		// put upcall pid 0
		req.putIntAttr(OVS_DP_ATTR_UPCALL_PID, 0);

		Future<BlockingQueue<ByteBuffer>> or =
				gnc.makeRequest(
						ovsDpFamily,
						(short) (GenericNetlinkChannel.NLM_F_REQUEST | GenericNetlinkChannel.NLM_F_ECHO),
						OVS_DP_CMD_NEW,
						OVS_DATAPATH_VERSION,
						req.getBuffer());

		ByteBuffer res = or.get().take();

		int dpIfIndex = res.getInt();

		System.out.println("dpIfIndex = " + dpIfIndex);

		NetlinkMessage resNl = new NetlinkMessage(res);

		String dpName = resNl.findStringAttr(OVS_DP_ATTR_NAME);

		System.out.println("dpName = " + dpName);

		return dpIfIndex;
	}

	void addPort(
			int dpIndex,
			int portType,
			String name) throws Exception {
		ByteBuffer buf = ByteBuffer.allocateDirect(64);
		buf.order(ByteOrder.nativeOrder());

		NetlinkMessage req = new NetlinkMessage(buf);

		// put in an int for the ovs_header->dp_index
		buf.putInt(dpIndex);

//		req.putIntAttr(PORT_NO, portId);
		req.putIntAttr(OVS_VPORT_ATTR_TYPE, portType);
		req.putStringAttr(OVS_VPORT_ATTR_NAME, name);
		req.putIntAttr(OVS_VPORT_ATTR_UPCALL_PID, gnc.getNetlinkChannel().getLocalPid());
//		req.put(ADDRESS, ethAddr);

		Future<BlockingQueue<ByteBuffer>> or =
				gnc.makeRequest(
						ovsVportFamily,
						(short) (GenericNetlinkChannel.NLM_F_REQUEST | GenericNetlinkChannel.NLM_F_ECHO),
						OVS_VPORT_CMD_NEW,
						OVS_VPORT_VERSION,
						req.getBuffer());

		ByteBuffer res = or.get().take();
	}

}
