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
import org.openflow.protocol.OFPortStatus;
import org.openflow.protocol.OFPortStatus.OFPortReason;
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
    protected OFGetConfigReply configReply;
    protected HashMap<Short, OFPhysicalPort> ports = new HashMap<Short, OFPhysicalPort>();

    public ControllerStubImpl(SocketChannel sock, Reactor reactor,
            Controller controller) throws IOException {
        super(sock, reactor);

        setController(controller);
    }
    
    public void start() {
        stream.write(factory.getMessage(OFType.HELLO));
        
        log.debug("start: start sending ECHO requests");
        sendEchoRequest();
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

    public OFFeaturesReply getFeatures() {
        return featuresReply;
    }

    protected void onConnectionLost() {
        // only remove if we have a features reply (DPID)
        if (featuresReply != null) {
            controller.onConnectionLost();
        }
    }
    
    protected void deleteAllFlows() {
        OFMatch match = new OFMatch().setWildcards(OFMatch.OFPFW_ALL);
        OFMessage fm = ((OFFlowMod) factory.getMessage(OFType.FLOW_MOD))
              .setMatch(match).setCommand(OFFlowMod.OFPFC_DELETE)
              .setOutPort(OFPort.OFPP_NONE).setLength(U16.t(OFFlowMod.MINIMUM_LENGTH));
        stream.write(fm);
    }
    
    protected void sendFeaturesRequest() {
        log.info("sendFeaturesRequest");
        
        OFFeaturesRequest m = (OFFeaturesRequest) factory.getMessage(OFType.FEATURES_REQUEST);
        m.setXid(initiateOperation(new SuccessHandler<OFFeaturesReply>() {
            @Override
            public void onSuccess(OFFeaturesReply data) {
                log.debug("received features reply");
                
                featuresReply = data;
                
                sendConfigRequest();
            }
        },

        new TimeoutHandler() {
            @Override
            public void onTimeout() {
                log.warn("features request timeout");
                
                if (socketChannel.isConnected()) {
                    sendFeaturesRequest();
                }
            }
        }, Long.valueOf(500), OFType.FEATURES_REQUEST));
        
        stream.write(m);
    }
    
    protected void sendConfigRequest() {
        log.info("sendConfigRequest");
        
        OFGetConfigRequest m = (OFGetConfigRequest) factory.getMessage(OFType.GET_CONFIG_REQUEST);
        m.setXid(initiateOperation(new SuccessHandler<OFGetConfigReply>() {
            @Override
            public void onSuccess(OFGetConfigReply data) {
                log.debug("received config reply");
                
                boolean firstTime = (null == configReply);
                
                if (firstTime) {
                    controller.onConnectionMade();
                }
            }
        },

        new TimeoutHandler() {
            @Override
            public void onTimeout() {
                log.warn("config request timeout");
                
                if (socketChannel.isConnected()) {
                    sendConfigRequest();
                }
            }
        }, Long.valueOf(500), OFType.GET_CONFIG_REQUEST));
        
        stream.write(m);
    }

    protected boolean handleMessage(OFMessage m) {
        log.debug("handleMessage");

        if (super.handleMessage(m)) {
            return true;
        }

        SuccessHandler successHandler = null;

        switch (m.getType()) {
        case HELLO:
            log.debug("handleMessage: HELLO");
            sendFeaturesRequest();
            deleteAllFlows();
            return true;
        case FEATURES_REPLY:
            log.debug("handleMessage: FEATURES_REPLY");
            successHandler = terminateOperation(m.getXid(), OFType.FEATURES_REQUEST);
            if (successHandler != null) {
                successHandler.onSuccess((OFFeaturesReply) m);
            }
            return true;
        case GET_CONFIG_REPLY:
            log.debug("handleMessage: GET_CONFIG_REPLY");
            OFGetConfigReply cr = (OFGetConfigReply) m;
            successHandler = terminateOperation(m.getXid(), OFType.GET_CONFIG_REQUEST);
            if (successHandler != null) {
                successHandler.onSuccess(cr);
            }
            return true;
        case BARRIER_REPLY:
            log.debug("handleMessage: BARRIER_REPLY");
            OFBarrierReply br = (OFBarrierReply) m;
            successHandler = terminateOperation(m.getXid(), OFType.BARRIER_REQUEST);
            if (successHandler != null) {
                successHandler.onSuccess(null);
            }
            return true;
        case PACKET_IN:
            log.debug("handleMessage: PACKET_IN");
            OFPacketIn pi = (OFPacketIn) m;
            controller.onPacketIn(pi.getBufferId(), pi.getTotalLength(), pi.getInPort(),
                    pi.getPacketData());
            return true;
        case FLOW_REMOVED:
            log.debug("handleMessage: FLOW_REMOVED");
            OFFlowRemoved fr = (OFFlowRemoved) m;
            controller.onFlowRemoved(fr.getMatch(), fr.getCookie(), fr.getPriority(),
                    fr.getReason(), fr.getDurationSeconds(), fr.getDurationNanoseconds(),
                    fr.getIdleTimeout(), fr.getPacketCount(), fr.getByteCount());
            return true;
        case PORT_STATUS:
            log.debug("handleMessage: PORT_STATUS");
            OFPortStatus ps = (OFPortStatus) m;
            controller.onPortStatus(ps.getDesc(), OFPortReason.values()[ps.getReason()]);
            return true;
        default:
            log.debug("handleMessages: default: " + m.getType());
            // let the controller handle any messages not handled here
            controller.onMessage(m);
            return true;
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
            short idleTimeoutSecs, short hardTimeoutSecs, short priority, 
            int bufferId, boolean sendFlowRemove, boolean checkOverlap,                     boolean emergency, List<OFAction> actions) {
        log.debug("sendFlowModAdd");

        short flags = 0;

        // Whether to send a OFPT_FLOW_REMOVED message when the flow expires 
        // or is deleted.
        if (sendFlowRemove)
            flags |= 1;

        if (checkOverlap)
            flags |= (1 << 1);

        if (emergency)
            flags |= (1 << 2);

        OFFlowMod fm = (OFFlowMod) factory.getMessage(OFType.FLOW_MOD);
        fm.setCommand(OFFlowMod.OFPFC_ADD);
        fm.setMatch(match).setCookie(cookie).setIdleTimeout(idleTimeoutSecs);
        fm.setHardTimeout(hardTimeoutSecs).setPriority(priority);
        fm.setBufferId(bufferId).setFlags(flags);
        
        fm.setActions(actions);
        
        int totalActionLength = 0;
        if (null != actions) {
            for (OFAction a : actions)
                totalActionLength += a.getLengthU();
        }
        fm.setLength(U16.t(OFFlowMod.MINIMUM_LENGTH + totalActionLength));
        
        log.debug("sendFlowModAdd: about to send {}", fm);

        // TODO(pino): remove after finding the root cause of Redmine #301.
        // OVS seems to always install nw_tos=0 regardless of what we write.
        // Wildcard the TOS as a quick workaround.
        int wc = match.getWildcards();
        match.setWildcards(wc | OFMatch.OFPFW_NW_TOS);
        stream.write(fm);
        // Not sure we need to do this... undo our change.
        match.setWildcards(wc);
        try {
            stream.flush();
        } catch (IOException e) {
            log.warn("sendFlowModAdd", e);
        }
    }

    @Override
    public void sendFlowModDelete(OFMatch match, boolean strict,
                                  short priority, short outPort) {
        log.debug("sendFlowModDelete");

        OFFlowMod fm = (OFFlowMod) factory.getMessage(OFType.FLOW_MOD);
        fm.setCommand(strict ? OFFlowMod.OFPFC_DELETE_STRICT 
                             : OFFlowMod.OFPFC_DELETE);
        fm.setMatch(match).setPriority(priority).setOutPort(outPort);

        stream.write(fm);
    }

    @Override
    public void sendPacketOut(int bufferId, short inPort, List<OFAction> actions, byte[] data) {
        log.debug("sendPacketOut buffer {} in_port {}", bufferId, inPort);

        OFPacketOut po = (OFPacketOut) factory.getMessage(OFType.PACKET_OUT);
        po.setBufferId(bufferId).setActions(actions);
        po.setInPort(inPort);
        po.setPacketData(data);
        po.setActions(actions);
        int totalActionLength = 0;
        if (null != actions) {
            for (OFAction a : actions)
                totalActionLength += a.getLengthU();
        }
        po.setActionsLength((short)totalActionLength);
        po.setLengthU(OFPacketOut.MINIMUM_LENGTH + totalActionLength +
                (null == data? 0 : data.length));
        stream.write(po);
        try {
            stream.flush();
        } catch (IOException e) {
            log.warn("sendPacketOut", e);
        }
    }

    @Override
    public void close() {
        try {
            socketChannel.close();
        } catch (IOException e) {
            log.warn("close", e);
        }
    }
}
