/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.netlink;

import java.nio.ByteBuffer;
import java.util.Set;

import org.midonet.util.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public abstract class AbstractGenericNetlinkConnection extends AbstractNetlinkConnection {

    private static final Logger log = LoggerFactory
            .getLogger(AbstractGenericNetlinkConnection.class);

    public AbstractGenericNetlinkConnection(NetlinkChannel channel, BufferPool sendPool) {
        super(channel, sendPool);
    }

    public void getFamilyId(String familyName, Callback<Short> callback) {
        getFamilyId(familyName, callback, DEF_REPLY_TIMEOUT);
    }

    public void getFamilyId(String familyName,
                            Callback<Short> callback, long timeoutMillis) {
        ByteBuffer buf = getBuffer();
        GenlProtocol.familyNameRequest(familyName, NLFlag.REQUEST, pid(),
                CtrlFamily.Context.GetFamily, buf);
        sendNetlinkMessage(buf, callback,
                CtrlFamily.familyIdDeserializer, timeoutMillis);
    }

    public void getMulticastGroup(String familyName,
                                  String groupName,
                                  Callback<Integer> callback) {
        getMulticastGroup(familyName, groupName, callback, DEF_REPLY_TIMEOUT);
    }

    public void getMulticastGroup(String familyName,
                                  String groupName,
                                  Callback<Integer> callback,
                                  long timeoutMillis) {
        ByteBuffer buf = getBuffer();
        GenlProtocol.familyNameRequest(familyName, NLFlag.REQUEST, pid(),
                CtrlFamily.Context.GetFamily, buf);
        sendNetlinkMessage(buf, callback,
                CtrlFamily.mcastGrpDeserializer(groupName), timeoutMillis);
    }

    /** Finalizes a ByteBuffer containing a msg payload and puts it in the
     *  internal queue of messages to be writen to the nl socket by the
     *  writing thread. It is assumed that this message will be answered by
     *  a unique reply message. */
    protected <T> void sendNetlinkMessage(ByteBuffer payload,
                                          Callback<T> callback,
                                          Reader<T> reader,
                                          long timeoutMillis) {
        enqueueRequest(NetlinkRequest.makeSingle(callback, reader,
                payload, timeoutMillis));
    }

    /** Same as sendNetlinkMessage(), but assumes that the message to be sent
     *  will be answered by multiple answers (enumerate requests). The reply
     *  handler will take care of assembling a set of answers using the given
     *  deserialisation function, and will pass this set to the given callback
     *  once a DONE netlink message is read for the handler seq number. */
    protected <T> void sendMultiAnswerNetlinkMessage(ByteBuffer payload,
                                                     Callback<Set<T>> callback,
                                                     Reader<T> reader,
                                                     long timeoutMillis) {
        enqueueRequest(NetlinkRequest.makeMulti(callback, reader,
                payload, timeoutMillis));
    }

    @Override
    protected void processMessageType(Bucket bucket, short type, short flags, int seq, int pid, ByteBuffer reply)
    {
        // read genl header
        byte cmd = reply.get();      // command
        byte ver = reply.get();      // version
        reply.getShort();            // reserved

        if (seq == 0) {
            // if the seq number is zero we are handling a PacketIn.
            if (bucket.consumeToken())
                handleNotification(type, cmd, seq, pid, reply);
            else
                log.debug("Failed to get token; dropping packet");
        } else  {
            // otherwise we are processing an answer to a request.
            processRequestAnswer(seq, flags, reply);
        }

    }

    protected int getHeaderLength() {
        // sizeof(netlink header) +
        // sizeof(generic netlink header)
        return 20;
    }

}
