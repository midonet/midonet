/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import org.openflow.protocol.OFBarrierReply;
import org.openflow.protocol.OFBarrierRequest;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFFeaturesRequest;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFGetConfigReply;
import org.openflow.protocol.OFGetConfigRequest;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.util.HexString;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;

public class ControllerStubImpl extends BaseProtocolImpl implements ControllerStub {

    private final static Logger log = LoggerFactory.getLogger(ControllerStubImpl.class);

    protected Controller controller;

    protected ConcurrentMap<Object, Object> attributes;
    protected Date connectedSince;
    protected OFFeaturesReply featuresReply;
    protected SocketChannel socketChannel;
    protected HashMap<Short, OFPhysicalPort> ports = new HashMap<Short, OFPhysicalPort>();

    public ControllerStubImpl(SocketChannel sock, Reactor reactor,
            Controller controller) throws IOException {
        super(sock, reactor);

        setController(controller);

        getFeaturesAsync(null, new TimeoutHandler() {
            @Override
            public void onTimeout() {
                disconnectSwitch();
            }
        }, defaultOperationTimeoutMillis);
    }

    @Override
    public void setController(Controller controller) {
        this.controller = controller;

        controller.setControllerStub(this);
    }

    public void doBarrierAsync(SuccessHandler successHandler, TimeoutHandler timeoutHandler,
            long timeoutMillis) {
        log.debug("doBarrierAsync");

        OFBarrierRequest msg = (OFBarrierRequest) factory.getMessage(OFType.BARRIER_REQUEST);
        msg.setXid(initiateOperation(successHandler, timeoutHandler, timeoutMillis,
                OFType.BARRIER_REQUEST));

        stream.write(msg);
    }

    @Override
    public void getConfigAsync(final ConfigHandler configHandler, TimeoutHandler timeoutHandler,
            long timeoutMillis) {
        log.debug("getConfigAsync");

        OFGetConfigRequest m = (OFGetConfigRequest) factory.getMessage(OFType.GET_CONFIG_REQUEST);
        m.setXid(initiateOperation(new SuccessHandler<OFGetConfigReply>() {
            @Override
            public void onSuccess(OFGetConfigReply data) {
                configHandler.onConfig(data);
            }
        }, timeoutHandler, timeoutMillis, OFType.GET_CONFIG_REQUEST));

        stream.write(m);
    }

    public void getFeaturesAsync(final FeaturesHandler featuresHandler,
            TimeoutHandler timeoutHandler, long timeoutMillis) {
        log.debug("getFeaturesAsync");

        OFFeaturesRequest m = (OFFeaturesRequest) factory.getMessage(OFType.FEATURES_REQUEST);
        m.setXid(initiateOperation(new SuccessHandler<OFFeaturesReply>() {
            @Override
            public void onSuccess(OFFeaturesReply data) {
                featuresHandler.onFeatures(data);
            }
        }, timeoutHandler, timeoutMillis, featuresHandler));

        stream.write(m);
    }

    public OFFeaturesReply getFeatures() {
        return featuresReply;
    }

    protected void onConnectionLost() {
        // only remove if we have a features reply (DPID)
        if (featuresReply != null) {
            controller.onConnectionLost();
        }
    }

    protected synchronized void handleMessage(OFMessage m) {
        log.debug("handleMessage");

        super.handleMessage(m);

        SuccessHandler successHandler = null;

        switch (m.getType()) {
        case HELLO:
            log.debug("handleMessage: HELLO");
            // Send initial Features Request
            stream.write(factory.getMessage(OFType.FEATURES_REQUEST));

            // Delete all pre-existing flows
            OFMatch match = new OFMatch().setWildcards(OFMatch.OFPFW_ALL);
            OFMessage fm = ((OFFlowMod) stream.getMessageFactory().getMessage(OFType.FLOW_MOD))
                    .setMatch(match).setCommand(OFFlowMod.OFPFC_DELETE)
                    .setOutPort(OFPort.OFPP_NONE).setLength(U16.t(OFFlowMod.MINIMUM_LENGTH));
            stream.write(fm);
            break;
        case FEATURES_REPLY:
            log.debug("handleMessage: FEATURES_REPLY");
            successHandler = terminateOperation(m.getXid(), OFType.FEATURES_REQUEST);
            if (successHandler != null) {
                boolean firstTime = (null == featuresReply);
                featuresReply = (OFFeaturesReply) m;

                if (firstTime) {
                    controller.onConnectionMade();
                }

                successHandler.onSuccess(featuresReply);
            }
            break;
        case GET_CONFIG_REPLY: {
            log.debug("handleMessage: GET_CONFIG_REPLY");
            OFGetConfigReply cr = (OFGetConfigReply) m;
            successHandler = terminateOperation(m.getXid(), OFType.GET_CONFIG_REQUEST);
            if (successHandler != null) {
                successHandler.onSuccess(cr);
            }
        }
            break;
        case BARRIER_REPLY:
            log.debug("handleMessage: BARRIER_REPLY");
            OFBarrierReply br = (OFBarrierReply) m;
            successHandler = terminateOperation(m.getXid(), OFType.BARRIER_REQUEST);
            if (successHandler != null) {
                successHandler.onSuccess(null);
            }
            break;
        case PACKET_IN:
            log.debug("handleMessage: PACKET_IN");
            OFPacketIn pi = (OFPacketIn) m;
            controller.onPacketIn(pi.getBufferId(), pi.getTotalLength(), pi.getInPort(),
                    pi.getPacketData());
            break;
        case FLOW_REMOVED:
            log.debug("handleMessage: FLOW_REMOVED");
            OFFlowRemoved fr = (OFFlowRemoved) m;
            controller.onFlowRemoved(fr.getMatch(), fr.getCookie(), fr.getPriority(),
                    fr.getReason(), fr.getDurationSeconds(), fr.getDurationNanoseconds(),
                    fr.getIdleTimeout(), fr.getPacketCount(), fr.getByteCount());
            break;
        default:
            log.debug("handleMessages: default: " + m.getType());
            // let the controller handle any messages not handled here
            controller.onMessage(m);
        }
    }

    @Override
    public String toString() {
        return "ControllerStubImpl ["
                + socketChannel.socket()
                + " DPID["
                + ((featuresReply != null) ? HexString.toHexString(featuresReply.getDatapathId())
                        : "?") + "]]";
    }

    @Override
    public void sendFlowModAdd(OFMatch match, long cookie, 
	    short idleTimeoutSecs, short priority, int bufferId, 
            boolean sendFlowRemove, boolean checkOverlap, boolean emergency,
            List<OFAction> actions, short outPort) {
        log.debug("sendFlowModAdd");

        short flags = 0;
        if (sendFlowRemove)
            flags |= 1;

        if (checkOverlap)
            flags |= (1 << 1);

        if (emergency)
            flags |= (1 << 2);

        OFFlowMod fm = (OFFlowMod) factory.getMessage(OFType.FLOW_MOD);
        fm.setCommand(OFFlowMod.OFPFC_ADD);
        fm.setMatch(match).setCookie(cookie).setIdleTimeout(idleTimeoutSecs).
		setPriority(priority).setBufferId(bufferId);
        fm.setFlags(flags).setActions(actions).setOutPort(outPort);

        stream.write(fm);
    }

    @Override
    public void sendFlowModDelete(OFMatch match, boolean strict,
	                          short priority, short outPort) {
        log.debug("sendFlowModDelete");

        OFFlowMod fm = (OFFlowMod) factory.getMessage(OFType.FLOW_MOD);
        fm.setCommand(strict ? OFFlowMod.OFPFC_DELETE_STRICT 
                             : OFFlowMod.OFPFC_DELETE);
        fm.setMatch(match).setPriority(priority);

        stream.write(fm);
    }

    @Override
    public void sendPacketOut(int bufferId, short inPort, List<OFAction> actions, byte[] data) {
        log.debug("sendPacketOut");

        OFPacketOut po = (OFPacketOut) factory.getMessage(OFType.PACKET_OUT);
        po.setBufferId(bufferId).setActions(actions);
        po.setInPort(inPort);
        po.setPacketData(data);

        stream.write(po);
    }

}
