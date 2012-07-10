/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.util.concurrent.ValueFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.netlink.AbstractNetlinkConnection;
import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.family.CtrlFamily;
import com.midokura.util.reactor.Reactor;
import static com.midokura.util.netlink.Netlink.Flag;

/**
 * Basic Netlink protocol implementation.
 */
public class NetlinkConnection extends AbstractNetlinkConnection {

    protected static final long DEF_REPLY_TIMEOUT = TimeUnit.SECONDS
                                                            .toMillis(1);

    private static final Logger log = LoggerFactory
        .getLogger(NetlinkConnection.class);

    final static CtrlFamily ctrlFamily = new CtrlFamily();

    public NetlinkConnection(NetlinkChannel channel, Reactor reactor) {
        super(channel, reactor);
    }

    public Future<Short> getFamilyId(@Nonnull String familyName) {
        ValueFuture<Short> future = ValueFuture.create();
        getFamilyId(familyName, wrapFuture(future));
        return future;
    }

    public void getFamilyId(@Nonnull String familyName, Callback<Short> callback) {
        getFamilyId(familyName, callback, DEF_REPLY_TIMEOUT);
    }

    public void getFamilyId(@Nonnull String familyName,
                            @Nonnull Callback<Short> callback, long timeoutMillis) {

        NetlinkMessage message =
            newMessage(64)
                .addAttr(CtrlFamily.Attr.FAMILY_NAME, familyName)
                .build();

        newRequest(ctrlFamily, CtrlFamily.Cmd.GETFAMILY)
            .withFlags(Flag.NLM_F_REQUEST)
            .withPayload(message.buf)
            .withCallback(callback, new Function<List<ByteBuffer>, Short>() {
                @Override
                public Short apply(@Nullable List<ByteBuffer> input) {
                    if (input == null || input.size() == 0 || input.get(0) == null)
                        return 0;

                    NetlinkMessage message = new NetlinkMessage(input.get(0));
                    // read result from res
                    return message.getAttrValue(CtrlFamily.Attr.FAMILY_ID);
                }
            })
            .withTimeout(timeoutMillis)
            .send();
    }

    public void getMulticastGroup(final String familyName,
                                  final String groupName,
                                  Callback<Integer> callback,
                                  long timeoutMillis)
        throws Exception {

        NetlinkMessage message =
            newMessage(64)
                .addAttr(CtrlFamily.Attr.FAMILY_NAME, familyName)
                .build();

        newRequest(ctrlFamily, CtrlFamily.Cmd.GETFAMILY)
            .withFlags(Flag.NLM_F_REQUEST)
            .withPayload(message.buf)
            .withCallback(callback, new Function<List<ByteBuffer>, Integer>() {
                @Override
                public Integer apply(@Nullable List<ByteBuffer> input) {
                    if (input == null)
                        return 0;

                    NetlinkMessage res = new NetlinkMessage(input.get(0));

                    NetlinkMessage sub = res.getAttrValue(CtrlFamily.Attr.MCAST_GROUPS);

                    while (sub.hasRemaining()) {
                        sub.buf.getShort();
                        sub.buf.getShort();

                        Integer id =
                            sub.getAttrValue(CtrlFamily.Attr.MCAST_GRP_ID);
                        String name =
                            sub.getAttrValue(CtrlFamily.Attr.MCAST_GRP_NAME);

                        if (name.equals(groupName)) {
                            return id;
                        }
                    }

                    return 0;
                }
            })
            .withTimeout(timeoutMillis)
            .send();
    }

}
