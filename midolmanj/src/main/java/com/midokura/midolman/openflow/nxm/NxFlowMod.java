/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionType;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NxFlowMod extends NxMessage {
    private final static Logger log = LoggerFactory.getLogger(NxFlowMod.class);
    private static final int MINIMUM_LENGTH = 48;

    protected long cookie;
    protected short command;
    protected short idleTimeout;
    protected short hardTimeout;
    protected short priority;
    protected int bufferId;
    protected short outPort = OFPort.OFPP_NONE.getValue();
    protected short flags;
    protected NxMatch nxm;
    protected List<OFAction> actions;

    // Stores the serialized NxFlowMod from prepareSerialize.
    private ByteBuffer serializedBuf;

    public NxFlowMod() {
        super(NxType.NXT_FLOW_MOD);
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        cookie = data.getLong();
        command = data.getShort();
        idleTimeout = data.getShort();
        hardTimeout = data.getShort();
        priority = data.getShort();
        bufferId = data.getInt();
        outPort = data.getShort();
        flags = data.getShort();
        int nxMatchLen = data.getShort() & 0xffff;
        // 6 bytes of padding
        for (int i=0; i<6; i++)
            data.get();
        int limit = data.limit();
        data.limit(data.position()+nxMatchLen);
        try {
            nxm = NxMatch.deserialize(data);
        } catch (NxmIOException e) {
            throw new RuntimeException(e);
        }
        data.limit(limit);

        // get padding bytes
        int zeroBytes = (nxMatchLen + 7)/8*8 - nxMatchLen;
        for (int i=0; i<zeroBytes; i++) {
            data.get();
        }

        actions = new ArrayList<OFAction>();
        OFAction demux = new OFAction();
        OFAction ofa;
        while (data.remaining() > 0) {
            data.mark();
            demux.readFrom(data);
            data.reset();
            OFActionType type = demux.getType();
            if(type.equals(OFActionType.VENDOR)) {
                NxAction nxa = new NxAction();
                nxa.readFrom(data);
                data.reset();
                Class<? extends OFAction> c = nxa.getSubtype().toClass();
                try {
                    ofa = c.getConstructor(new Class[]{}).newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                Class<? extends OFAction> c = type.toClass();
                try {
                    ofa = c.getConstructor(new Class[]{}).newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            ofa.readFrom(data);
            actions.add(ofa);
        }
    }

    /**
     * Must be called before trying to serialize. OFMessageAsyncStream queries
     * the length before calling writeTo in order to decide whether the
     * serialized message will fit in the output buffer.
     */
    public void prepareSerialize() {
        // calculate match len, which could be 0
        ByteBuffer nxmBuffer = null;
        int nxMatchLen = 0;

        if (nxm != null) {
            nxmBuffer = nxm.serialize();
            nxMatchLen = nxmBuffer.limit();
        }
        int zeroBytes = (nxMatchLen + 7)/8*8 - nxMatchLen;

        int totalActionLength = 0;
        if (null != actions) {
            for (OFAction a : actions)
                totalActionLength += a.getLengthU();
        }

        log.debug("writeTo: set OFMessageLength to {} (min length) + {} " +
                "(nxMatchLen) + {} zeroBytes + {} (actionsLen).",
                new Object[] {MINIMUM_LENGTH, nxMatchLen, zeroBytes,
                        totalActionLength});
        int totalLen =
                MINIMUM_LENGTH + nxMatchLen + zeroBytes + totalActionLength;
        super.setLength(U16.t(totalLen));
        ByteBuffer data = ByteBuffer.allocate(totalLen);

        // now that we set the length, we can write the header
        super.writeTo(data);
        data.putLong(cookie);
        data.putShort(command);
        data.putShort(idleTimeout);
        data.putShort(hardTimeout);
        data.putShort(priority);
        data.putInt(bufferId);
        data.putShort(outPort);
        data.putShort(flags);
        data.putShort((short) nxMatchLen);

        // 6 bytes of padding
        for (int i=0; i<6; i++)
            data.put((byte) 0);

        if (nxmBuffer != null) {
            data.put(nxmBuffer);
        }

        // put zero bytes for padding
        for (int i=0; i<zeroBytes; i++) {
            data.put((byte) 0);
        }

        if (actions != null) {
            for (OFAction action : actions) {
                action.writeTo(data);
            }
        }
        data.flip();
        serializedBuf = data;
    }

    @Override
    public void writeTo(ByteBuffer data) {
        if (null == serializedBuf)
            prepareSerialize();
        data.put(serializedBuf);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxFlowMod flowMod = (NxFlowMod) o;

        if (bufferId != flowMod.bufferId) return false;
        if (command != flowMod.command) return false;
        if (cookie != flowMod.cookie) return false;
        if (flags != flowMod.flags) return false;
        if (hardTimeout != flowMod.hardTimeout) return false;
        if (idleTimeout != flowMod.idleTimeout) return false;
        if (outPort != flowMod.outPort) return false;
        if (priority != flowMod.priority) return false;
        if (actions != null ?
                !actions.equals(flowMod.actions) : flowMod.actions != null)
            return false;
        if (nxm != null ? !nxm.equals(flowMod.nxm) : flowMod.nxm != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (int) (cookie ^ (cookie >>> 32));
        result = 31 * result + (int) command;
        result = 31 * result + (int) idleTimeout;
        result = 31 * result + (int) hardTimeout;
        result = 31 * result + (int) priority;
        result = 31 * result + bufferId;
        result = 31 * result + (int) outPort;
        result = 31 * result + (int) flags;
        result = 31 * result + (nxm != null ? nxm.hashCode() : 0);
        result = 31 * result + (actions != null ? actions.hashCode() : 0);
        return result;
    }

    public static NxFlowMod flowModAdd(NxMatch match,
            List<OFAction> actions, int bufferId, long cookie, short priority,
            short flags, short idleTimeoutSecs, short hardTimoutSecs) {
        NxFlowMod fm = new NxFlowMod();
        fm.command = OFFlowMod.OFPFC_ADD;
        fm.nxm = match;
        fm.actions = actions;
        fm.bufferId = bufferId;
        fm.cookie = cookie;
        fm.priority = priority;
        fm.flags = flags;
        fm.idleTimeout = idleTimeoutSecs;
        fm.hardTimeout = hardTimoutSecs;
        return fm;
    }

    public static NxFlowMod flowModDelete(NxMatch match, boolean strict,
                                          short priority, short outPort) {
        NxFlowMod fm = new NxFlowMod();
        fm.command = strict ? OFFlowMod.OFPFC_DELETE_STRICT
                : OFFlowMod.OFPFC_DELETE;
        fm.nxm = match;
        fm.priority = priority;
        fm.outPort = outPort;
        return fm;
    }
}
