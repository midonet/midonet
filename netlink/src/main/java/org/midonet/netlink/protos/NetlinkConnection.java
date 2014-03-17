/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink.protos;

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

import org.midonet.netlink.AbstractNetlinkConnection;
import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.CtrlFamily;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
import org.midonet.util.eventloop.Reactor;

import static org.midonet.netlink.Netlink.Flag;

/**
 * Basic Netlink protocol implementation.
 */
public class NetlinkConnection extends AbstractNetlinkConnection {

    protected static final long DEF_REPLY_TIMEOUT = TimeUnit.SECONDS
                                                            .toMillis(1);

    private static final Logger log = LoggerFactory
        .getLogger(NetlinkConnection.class);

    public NetlinkConnection(NetlinkChannel channel, Reactor reactor,
                             BufferPool sendPool) {
        super(channel, reactor, sendPool);
    }

    @Override
    protected void handleNotification(short type, byte cmd, int seq, int pid, List<ByteBuffer> buffers) {
        log.error("Notification handler not implemented: {family: {}, cmd: {}}", type, cmd);
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

        Builder builder = newMessage();
        builder.addAttr(CtrlFamily.AttrKey.FAMILY_NAME, familyName);
        NetlinkMessage message = builder.build();

        Function<List<ByteBuffer>, Short> familyIdDeserializer =
            new Function<List<ByteBuffer>, Short>() {
                @Override
                public Short apply(@Nullable List<ByteBuffer> input) {
                    if (input == null || input.isEmpty() || input.get(0) == null)
                        return 0;

                    NetlinkMessage message = new NetlinkMessage(input.get(0));
                    // read result from res
                    return message.getAttrValueShort(CtrlFamily.AttrKey.FAMILY_ID);
                }
            };

        sendNetlinkMessage(
            CtrlFamily.Context.GetFamily,
            Flag.or(Flag.NLM_F_REQUEST),
            message.getBuffer(),
            callback,
            familyIdDeserializer,
            timeoutMillis);
    }

    public void getMulticastGroup(final String familyName,
                                  final String groupName,
                                  Callback<Integer> callback) {
        getMulticastGroup(familyName, groupName, callback, DEF_REPLY_TIMEOUT);
    }

    public void getMulticastGroup(final String familyName,
                                  final String groupName,
                                  Callback<Integer> callback,
                                  long timeoutMillis) {

        Builder builder = newMessage();
        builder.addAttr(CtrlFamily.AttrKey.FAMILY_NAME, familyName);
        NetlinkMessage message = builder.build();

        Function<List<ByteBuffer>, Integer> mcastGrpDeserializer =
            new Function<List<ByteBuffer>, Integer>() {
                @Override
                public Integer apply(@Nullable List<ByteBuffer> input) {
                    if (input == null)
                        return null;

                    NetlinkMessage res = new NetlinkMessage(input.get(0));

                    NetlinkMessage sub = res.getAttrValueNested(CtrlFamily.AttrKey.MCAST_GROUPS);

                    if (sub == null)
                        return null;

                    sub.getShort();
                    sub.getShort();

                    String name = sub.getAttrValueString(CtrlFamily.AttrKey.MCAST_GRP_NAME);
                    if ( name.equals(groupName) )
                        return sub.getAttrValueInt(CtrlFamily.AttrKey.MCAST_GRP_ID);

                    return null;
                }
            };

        sendNetlinkMessage(
            CtrlFamily.Context.GetFamily,
            Flag.or(Flag.NLM_F_REQUEST),
            message.getBuffer(),
            callback,
            mcastGrpDeserializer,
            timeoutMillis);
    }

}
