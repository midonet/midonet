package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;
import java.util.List;

import org.openflow.protocol.action.OFAction;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NxFlowMod extends NxMessage {
    private final static Logger log = LoggerFactory.getLogger(NxFlowMod.class);
    private static final int MINIMUM_LENGTH = 48;

    private long cookie;
    private short command;
    private short idleTimeout;
    private short hardTimeout;
    private short priority;
    private int bufferId;
    private short outPort;
    private short flags;
    private NxMatch nxm;
    private List<OFAction> actions;

    // Stores the serialized NxFlowMod from prepareSerialize.
    private ByteBuffer serializedBuf;

    public NxFlowMod() {
        super(13); // NXT_FLOW_MOD
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
            nxmBuffer.flip();
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
        serializedBuf = data;
    }

    @Override
    public void writeTo(ByteBuffer data) {
        if (null == serializedBuf)
            prepareSerialize();
        serializedBuf.flip();
        data.put(serializedBuf);
    }

    public void setCookie(long cookie) {
        this.cookie = cookie;
    }

    public void setCommand(short command) {
        this.command = command;
    }

    public void setIdleTimeout(short idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public void setHardTimeout(short hardTimeout) {
        this.hardTimeout = hardTimeout;
    }

    public void setPriority(short priority) {
        this.priority = priority;
    }

    public void setBufferId(int bufferId) {
        this.bufferId = bufferId;
    }

    public void setOutPort(short outPort) {
        this.outPort = outPort;
    }

    public void setFlags(short flags) {
        this.flags = flags;
    }

    public void setMatch(NxMatch nxm) {
        this.nxm = nxm;
    }

    public void setActions(List<OFAction> actions) {
        this.actions = actions;
    }
}
