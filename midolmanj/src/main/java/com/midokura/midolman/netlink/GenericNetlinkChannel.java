/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.netlink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractFuture;
import sun.nio.ch.NetlinkChannelImpl;

import com.midokura.midolman.eventloop.SelectListener;

public class GenericNetlinkChannel implements SelectListener {

	private final static Logger log = LoggerFactory.getLogger(GenericNetlinkChannel.class);

	static final int NETLINK_GENERIC = 16;

	static final short NLMSG_NOOP = 1;
	static final short NLMSG_ERROR = 2;
	static final short NLMSG_DONE = 3;
	static final short NLMSG_OVERRUN = 4;

	static final byte GENL_VERSION = (byte) 1;

	static final short GENL_ID_CTRL = 0x10;

	static final short NLM_F_REQUEST = 0x001;
	static final short NLM_F_MULTI = 0x002;
	static final short NLM_F_ACK = 0x004;
	static final short NLM_F_ECHO = 0x008;

	static final short NLM_F_ROOT = 0x100;
	static final short NLM_F_MATCH = 0x200;
	static final short NLM_F_ATOMIC = 0x400;
	static final short NLM_F_DUMP = (NLM_F_ROOT | NLM_F_MATCH);

	static final byte CTRL_CMD_GETFAMILY = 3;

	static final short CTRL_ATTR_FAMILY_ID = 1;
	static final short CTRL_ATTR_FAMILY_NAME = 2;
	static final short CTRL_ATTR_FAMILY_VERSION = 3;
	static final short CTRL_ATTR_HDRSIZE = 4;
	static final short CTRL_ATTR_MAXATTR = 5;
	static final short CTRL_ATTR_OPS = 6;
	static final short CTRL_ATTR_MCAST_GROUPS = 7;

	static final short CTRL_ATTR_MCAST_GRP_NAME = 1;
	static final short CTRL_ATTR_MCAST_GRP_ID = 2;

	private NetlinkChannelImpl nc;

	private AtomicInteger sequence = new AtomicInteger(1);

	/**
	 * TODO: improve this interface
	 * there's not way to know when the last fragment is being read
	 * else we have to wait for all fragments to come back
	 * and then signal, which is not ideal if there are many fragments
	 * such as when enumerating the flow table
	 */
	class OutstandingRequest extends AbstractFuture<BlockingQueue<ByteBuffer>> {

		BlockingQueue<ByteBuffer> bufs = new ArrayBlockingQueue<ByteBuffer>(10);

		void finish() {
			super.set(bufs);
		}

		@Override
		protected boolean setException(Throwable throwable) {
			return super.setException(throwable);
		}

	}

	private Map<Integer, OutstandingRequest> outstanding = new ConcurrentHashMap<Integer, OutstandingRequest>();

	public GenericNetlinkChannel(SelectorProvider sp, int remotePid) throws IOException {
		nc = new NetlinkChannelImpl(sp, NETLINK_GENERIC);
		nc.connect(remotePid);
	}

	public NetlinkChannelImpl getNetlinkChannel() {
		return nc;
	}

	public short getFamilyId(String familyName) throws Exception {
		NetlinkMessage req = new NetlinkMessage(64);
		req.putStringAttr(CTRL_ATTR_FAMILY_NAME, familyName);

		Future<BlockingQueue<ByteBuffer>> or = makeRequest(
				GENL_ID_CTRL,
				NLM_F_REQUEST,
				CTRL_CMD_GETFAMILY,
				GENL_VERSION,
				req.getBuffer());

		NetlinkMessage res = new NetlinkMessage(or.get().take());

		// read result from res
		return res.findShortAttr(CTRL_ATTR_FAMILY_ID);
	}

	public Integer getMulticastGroup(String familyName, String groupName) throws Exception {
		NetlinkMessage req = new NetlinkMessage(64);
		req.putStringAttr(CTRL_ATTR_FAMILY_NAME, familyName);

		Future<BlockingQueue<ByteBuffer>> or = makeRequest(
				GENL_ID_CTRL,
				NLM_F_REQUEST,
				CTRL_CMD_GETFAMILY,
				GENL_VERSION,
				req.getBuffer());

		NetlinkMessage res = new NetlinkMessage(or.get().take());

		NetlinkMessage sub = res.findNested(CTRL_ATTR_MCAST_GROUPS);

		while (sub.hasRemaining()) {
			sub.buf.getShort();
			sub.buf.getShort();

			Integer id = sub.getIntAttr(CTRL_ATTR_MCAST_GRP_ID);
			String name = sub.getStringAttr(CTRL_ATTR_MCAST_GRP_NAME);

			if (name.equals(groupName)) {
				return id;
			}
		}

		return null;
	}

	public Future<BlockingQueue<ByteBuffer>> makeRequest(
			short family,
			short flags,
			byte cmd,
			byte version,
			ByteBuffer req) throws IOException {

		OutstandingRequest or = new OutstandingRequest();
		int seq = sequence.getAndIncrement();

		outstanding.put(seq, or);

		ByteBuffer request = ByteBuffer.allocateDirect(1024);
		request.order(ByteOrder.nativeOrder());

		int totalSize = 20 + req.remaining();

		putGenlMsgHeader(
				request,
				family,
				flags,
				seq,
				cmd,
				version
				);

		request.put(req);

		// set the header length
		request.putInt(0, totalSize);

		request.flip();

		// send the request
		int n = nc.write(request);

		System.out.println("wrote request " + n);

		return or;
	}

	private int putGenlMsgHeader(ByteBuffer request, short family, short flags, int seq, byte cmd, byte version) {
		int startPos = request.position();

		// put netlink header
		request.putInt(0); // nlmsg_len
		request.putShort(family); // nlmsg_type
		request.putShort(flags); // nlmsg_flags
		request.putInt(seq); // nlmsg_seq
		request.putInt(nc.getLocalPid()); // nlmsg_pid

		// put genl header
		request.put(cmd); // cmd
		request.put(version); // version
		request.putShort((short) 0); // reserved

		return request.position() - startPos;
	}

	private int putStringNlAttr(ByteBuffer buf, short nlType, String str) {
		int startPos = buf.position();

		int strLen = str.length() + 1;

		// put nl_attr for string
		buf.putShort((short) (4 + strLen)); // nla_len
		buf.putShort(nlType); // nla_type

		// put the string
		buf.put(str.getBytes());
		buf.put((byte) 0); // put a null terminator

		// pad
		int padLen = (int) (Math.ceil(strLen / 4.0) * 4) - strLen;
		for (int i=0; i<padLen; i++) {
			buf.put((byte) 0);
		}

		return buf.position() - startPos;
	}

	int putIntNlAttr(ByteBuffer buf, short nlType, int val) {
		// put nl_attr for string
		buf.putShort((short) 8); // nla_len
		buf.putShort(nlType); // nla_type

		// put the value
		buf.putInt(val);

		return 8;
	}

	@Override
	public void handleEvent(SelectionKey key) throws IOException {

		// allocate buffer for the reply
		ByteBuffer reply = ByteBuffer.allocateDirect(1024);
		reply.order(ByteOrder.nativeOrder());

		// read the reply
		nc.read(reply);

		reply.flip();

		System.out.println("read reply " + reply);

		reply.mark();

		// read the nlmsghdr and check for error
		int len = reply.getInt();
		short type = reply.getShort();
		short flags = reply.getShort();
		int seq = reply.getInt();
		int pid = reply.getInt();

		OutstandingRequest or = outstanding.get(seq);
		if (or == null) {
			log.warn("couldn't find outstanding for seq = " + seq);
		}

		if (type == NLMSG_ERROR) {

			int error = reply.getInt();

			// read header which caused the error
			int errLen = reply.getInt();
			short errType = reply.getShort();
			short errFlags = reply.getShort();
			int errSeq = reply.getInt();
			int errPid = reply.getInt();

			outstanding.remove(seq);

			or.setException(new Exception("error = " + error));

		} else {

			// read genl header
			byte genlCmd = reply.get();
			byte genlVer = reply.get();
			reply.getShort(); // reserved

			if (type != NLMSG_DONE) {
				ByteBuffer payload = reply.slice();
				payload.order(ByteOrder.nativeOrder());

				or.bufs.offer(payload);
			}

			if (type == NLMSG_DONE || (flags & NLM_F_MULTI) == 0) {
				outstanding.remove(seq);

				or.finish();
			}
		}
	}

}
