/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openflow;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import static java.lang.String.format;

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
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFVendor;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;
import org.openflow.protocol.statistics.OFAggregateStatisticsRequest;
import org.openflow.protocol.statistics.OFDescriptionStatistics;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
import org.openflow.protocol.statistics.OFQueueStatisticsReply;
import org.openflow.protocol.statistics.OFQueueStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
import org.openflow.protocol.statistics.OFTableStatistics;
import org.openflow.util.HexString;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.nxm.MatchTranslation;
import com.midokura.midolman.openflow.nxm.NxFlowMod;
import com.midokura.midolman.openflow.nxm.NxFlowRemoved;
import com.midokura.midolman.openflow.nxm.NxMatch;
import com.midokura.midolman.openflow.nxm.NxMessage;
import com.midokura.midolman.openflow.nxm.NxPacketIn;
import com.midokura.midolman.openflow.nxm.NxSetFlowFormat;
import com.midokura.midolman.openflow.nxm.NxSetPacketInFormat;
import com.midokura.midolman.openflow.nxm.OfNxTunIdNxmEntry;

public class ControllerStubImpl extends BaseProtocolImpl implements ControllerStub {

    private final static Logger log = LoggerFactory.getLogger(ControllerStubImpl.class);
    private final static int FLOW_REQUEST_BODY_LENGTH = 44;
    private final static int PORT_QUEUE_REQUEST_BODY_LENGTH = 8;

    protected Controller controller;

    protected ConcurrentMap<Object, Object> attributes;
    protected OFFeaturesReply featuresReply;
    protected OFGetConfigReply configReply;
    protected HashMap<Short, OFPhysicalPort> ports = new HashMap<Short, OFPhysicalPort>();
    private boolean nxm_enabled = false;

    public ControllerStubImpl(SocketChannel sock, Reactor reactor,
            Controller controller) throws IOException {
        super(sock, reactor);

        setController(controller);
    }

    public void start() throws IOException {
        write(factory.getMessage(OFType.HELLO));

        log.debug("start: start sending ECHO requests");
        sendEchoRequest();
    }

    @Override
    public void setController(Controller controller) {
        this.controller = controller;

        controller.setControllerStub(this);
    }

    public void doBarrierAsync(SuccessHandler successHandler, TimeoutHandler timeoutHandler,
            long timeoutMillis) throws IOException {
        log.debug("doBarrierAsync");

        OFBarrierRequest msg = (OFBarrierRequest) factory.getMessage(OFType.BARRIER_REQUEST);
        msg.setXid(initiateOperation(successHandler, timeoutHandler, timeoutMillis,
                OFType.BARRIER_REQUEST));

        write(msg);
    }

    private boolean isValidStatsType(OFStatisticsType type) {
        EnumSet statsTypes = EnumSet.allOf(OFStatisticsType.class);
        if (statsTypes.contains(type))
            return true;
        else
            return false;
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

    protected void deleteAllFlows() throws IOException {
        OFMatch match = new OFMatch().setWildcards(OFMatch.OFPFW_ALL);
        OFMessage fm = ((OFFlowMod) factory.getMessage(OFType.FLOW_MOD))
              .setMatch(match).setCommand(OFFlowMod.OFPFC_DELETE)
              .setOutPort(OFPort.OFPP_NONE).setLength(U16.t(OFFlowMod.MINIMUM_LENGTH));
        write(fm);
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
        }, 500l, OFType.FEATURES_REQUEST));

        try {
            write(m);
        } catch (IOException e) {
            log.warn("sendFeaturesRequest", e);
        }
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
        }, 500l, OFType.GET_CONFIG_REQUEST));

        try {
            write(m);
        } catch (IOException e) {
            log.warn("sendConfigRequest", e);
        }
    }

    private boolean isValidStatsRequestLength(
            OFStatisticsRequest msg, int size) {
        switch (msg.getStatisticType()) {
            case DESC:  case TABLE:
                return (msg.getLengthU() ==
                        OFStatisticsRequest.MINIMUM_LENGTH);
            case AGGREGATE:  case FLOW:
                return (msg.getLengthU() ==
                        (OFStatisticsRequest.MINIMUM_LENGTH +
                                FLOW_REQUEST_BODY_LENGTH * size));
            case PORT:  case QUEUE:
                return (msg.getLengthU() ==
                        (OFStatisticsRequest.MINIMUM_LENGTH +
                                PORT_QUEUE_REQUEST_BODY_LENGTH * size));
            default:
                return false;
        }
    }

    private <StatsReply extends OFStatistics> void sendStatsRequest(
        final List<OFStatistics> requests, final OFStatisticsType type,
        final SuccessHandler<List<StatsReply>> onSuccess,
        long timeout, TimeoutHandler onTimeout)
    {
        log.debug("sendStatsRequest");

        if (!isValidStatsType(type))
            throw new OpenFlowError("invalid Openflow statistic type request");

        final OFStatisticsRequest request = new OFStatisticsRequest();

        request.setStatisticType(type);
        request.setStatistics(requests);
        for (OFStatistics statsRequest : requests) {
            request.setLengthU(
                request.getLengthU() + statsRequest.getLength());
        }

        if (request.getLengthU() < OFStatisticsRequest.MINIMUM_LENGTH)
            throw
                new OpenFlowError(
                    format("OFPT_STATS_REQUEST message is too short (len: %d)",
                           request.getLengthU()));

        @SuppressWarnings("unchecked")
        final Class<StatsReply> replyClass =
            (Class<StatsReply>) type.toClass(OFType.STATS_REPLY);

        final int xid = initiateOperation(
            new SuccessHandler<OFStatisticsReply>() {
                @Override
                public void onSuccess(OFStatisticsReply reply) {

                    if (reply.getStatistics().isEmpty())
                        log.debug("No response");

                    if (onSuccess != null) {
                        onSuccess.onSuccess(
                            extractStatsList(reply, replyClass));
                    }
                }
            }, onTimeout, timeout, OFType.STATS_REQUEST);

        request.setXid(xid);
        log.debug("Initiated stats operation with id: {}", request.getXid());
        if (!isValidStatsRequestLength(request, requests.size()))
            throw
                new OpenFlowError(
                    format("OFPT_STATS_REQUEST message has invalid length: %d",
                           request.getLengthU()));

        try {
            write(request);
        } catch (IOException e) {
            log.warn("sendStatsRequest", e);
        }

        log.debug("sent OFPT_STATS_REQUEST message with length {}.",
                  request.getLengthU());
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private <S extends OFStatistics>
    List<S> extractStatsList(OFStatisticsReply reply, Class<S> statsTypeClass) {
        if (reply == null) {
            log.debug("Can't get list from null OFStatisticsReply.");
            return null;
        }

        if (statsTypeClass.isAssignableFrom(
            reply.getStatisticType().toClass(OFType.STATS_REPLY))) {
            return (List<S>) reply.getStatistics();
        }

        log.error(
            "Invalid statistics reply of type: {} but we expected types " +
                "of {}. Reply was dropped.",
            reply.getStatisticType(), statsTypeClass);

        return null;
    }


    public void sendDescStatsRequest(SuccessHandler<List<OFDescriptionStatistics>> onSuccess,
                                     long timeout, TimeoutHandler onTimeout) {
        log.debug("OFPT_STATS_REQUEST / OFPST_DESC");

        sendStatsRequest(new ArrayList<OFStatistics>(), OFStatisticsType.DESC,
                         onSuccess, timeout, onTimeout);
    }

    public void sendTableStatsRequest(SuccessHandler<List<OFTableStatistics>> onSuccess,
                                      long timeout, TimeoutHandler onTimeout) {
        log.debug("OFPT_STATS_REQUEST / OFPST_TABLE");

        sendStatsRequest(new ArrayList<OFStatistics>(), OFStatisticsType.TABLE,
                         onSuccess, timeout, onTimeout);
    }

    public void sendFlowStatsRequest(OFMatch match, byte tableId, short outPort,
                                     SuccessHandler<List<OFFlowStatisticsReply>> onSuccess,
                                     long timeout, TimeoutHandler onTimeout) {
        log.debug("OFPT_STATS_REQUEST / OFPST_FLOW");

        OFFlowStatisticsRequest request = new OFFlowStatisticsRequest();
        request.setMatch(match);
        request.setTableId(tableId);
        request.setOutPort(outPort);

        sendStatsRequest(Arrays.asList((OFStatistics)request), OFStatisticsType.FLOW,
                         onSuccess, timeout, onTimeout);
    }

    public void sendAggregateStatsRequest(OFMatch match, byte tableId, short outPort,
                                          SuccessHandler<List<OFAggregateStatisticsReply>> onSuccess,
                                          long timeout, TimeoutHandler onTimeout) {
        log.debug("OFPT_STATS_REQUEST / OFPST_AGGREGATE: match={}, " +
                      "tableId={], outPort={}",
                  new Object[]{match, tableId, outPort});

        OFAggregateStatisticsRequest request = new OFAggregateStatisticsRequest();

        request.setMatch(match);
        request.setTableId(tableId);
        request.setOutPort(outPort);

        sendStatsRequest(
            Arrays.asList((OFStatistics)request), OFStatisticsType.AGGREGATE,
            onSuccess, timeout, onTimeout);
    }

    public void sendPortStatsRequest(short portNo,
                                     SuccessHandler<List<OFPortStatisticsReply>> onSuccess,
                                     long timeout, TimeoutHandler onTimeout)
    {
        log.debug("OFPT_STATS_REQUEST / OFPST_PORT: portNo={}", portNo);

        OFPortStatisticsRequest request = new OFPortStatisticsRequest();
        request.setPortNumber(portNo);

        sendStatsRequest(
            Arrays.asList((OFStatistics)request),
            OFStatisticsType.PORT, onSuccess, timeout, onTimeout);
    }

    public void sendQueueStatsRequest(short portNo, int queueId,
                                      SuccessHandler<List<OFQueueStatisticsReply>> onSuccess,
                                      long timeout, TimeoutHandler onTimeout)
    {
        log.debug("OFPT_STATS_REQUEST / OFPST_QUEUE: portNo={}, queueId={}",
                portNo, queueId);

        OFQueueStatisticsRequest queueStatsRequest =
                new OFQueueStatisticsRequest();

        queueStatsRequest.setPortNumber(portNo);
        queueStatsRequest.setQueueId(queueId);

        sendStatsRequest(Arrays.asList((OFStatistics)queueStatsRequest),
                         OFStatisticsType.QUEUE,
                         onSuccess, timeout, onTimeout);
    }

    @SuppressWarnings("unchecked")
    public void sendQueueStatsRequest(Map<Short, Set<Integer>> queueRequests,
                                      SuccessHandler<List<OFQueueStatisticsReply>> onSuccess,
                                      long timeout, TimeoutHandler onTimeout) {
        String debugString = "";
        for (short portNum : queueRequests.keySet())
            debugString += new StringBuilder().append(" portNo=").append(
                    queueRequests.get(portNum)).append(", queueIds=").append(
                    queueRequests.values());
        log.debug("OFPT_STATS_REQUEST / OFPST_QUEUE: {}", debugString);
        List<OFStatistics> queueStatsRequests =
                new ArrayList<OFStatistics>(queueRequests.size());
        for (short portNum: queueRequests.keySet())
            for (int queueNum: queueRequests.get(portNum)) {
                OFQueueStatisticsRequest queueStatsRequest =
                    new OFQueueStatisticsRequest();
                queueStatsRequest.setPortNumber(portNum);
                queueStatsRequest.setQueueId(queueNum);

                queueStatsRequests.add(queueStatsRequest);
            }

        sendStatsRequest(queueStatsRequests, OFStatisticsType.QUEUE,
                         onSuccess, timeout, onTimeout);
    }

    @Override
    protected boolean handleMessage(OFMessage m) throws IOException {
        log.debug("handleMessage");

        if (super.handleMessage(m)) {
            return true;
        }

        SuccessHandler successHandler;

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
                    pi.getPacketData(), 0);
            return true;
        case FLOW_REMOVED:
            log.debug("handleMessage: FLOW_REMOVED");
            OFFlowRemoved fr = (OFFlowRemoved) m;
            controller.onFlowRemoved(fr.getMatch(), fr.getCookie(),
                    fr.getPriority(), fr.getReason(), fr.getDurationSeconds(),
                    fr.getDurationNanoseconds(), fr.getIdleTimeout(),
                    fr.getPacketCount(), fr.getByteCount(), 0);
            return true;
        case PORT_STATUS:
            log.debug("handleMessage: PORT_STATUS");
            OFPortStatus ps = (OFPortStatus) m;
            controller.onPortStatus(ps.getDesc(), OFPortReason.values()[ps.getReason()]);
            return true;
        case STATS_REPLY:
            log.debug("handleMessage: STATS_REPLY / OFPST_{}",
                    ((OFStatisticsReply) m).getStatisticType());
            successHandler = terminateOperation(m.getXid(), OFType.STATS_REQUEST);
            if (successHandler != null)
                successHandler.onSuccess(m);
            return true;
        case VENDOR: {
            log.debug("handleMessage: VENDOR");
            OFVendor vm = (OFVendor) m;
            int vendor = vm.getVendor();

            if (vendor == NxMessage.NX_VENDOR_ID) {
                NxMessage nxm = NxMessage.fromOFVendor(vm);
                switch(nxm.getNxType()) {
                    case NXT_FLOW_REMOVED:
                        onNxFlowRemoved(NxFlowRemoved.class.cast(nxm));
                        break;
                    case NXT_PACKET_IN:
                        onNxPacketIn(NxPacketIn.class.cast(nxm));
                        break;
                    default:
                        log.warn("handleMessage: VENDOR {} - unhandled " +
                                "subtype {}", NxMessage.NX_VENDOR_ID,
                                nxm.getNxType());
                }
            } else {
                log.warn("handleMessage: VENDOR - unhandled vendor 0x{}",
                        Integer.toHexString(vendor));
            }
            return true;
        }
        default:
            log.debug("handleMessages: default: " + m.getType());
            // let the controller handle any messages not handled here
            controller.onMessage(m);
            return true;
        }
    }

    private void onNxPacketIn(NxPacketIn nxm) {
        // Look for supported NxmEntry types in the NxMatch.
        NxMatch match = nxm.getNxMatch();
        short inPort = match.getInPortEntry().getValue();
        OfNxTunIdNxmEntry tunEntry = match.getTunnelIdEntry();
        log.debug("onNxPacketIn: bufferId={} totalLen={} nxm={}",
                new Object[] {
                        nxm.getBufferId(), nxm.getTotalFrameLen(), match});

        long tunId = 0;
        if (null != tunEntry) {
            tunId = tunEntry.getTunnelId();
        }
        controller.onPacketIn(nxm.getBufferId(), nxm.getTotalFrameLen(),
                inPort, nxm.getPacket(), tunId);
    }

    private void onNxFlowRemoved(NxFlowRemoved nxm) {
        NxMatch match = nxm.getNxMatch();
        OfNxTunIdNxmEntry tunEntry = match.getTunnelIdEntry();
        controller.onFlowRemoved(MatchTranslation.toOFMatch(match),
                nxm.getCookie(), nxm.getPriority(), nxm.getReason(),
                nxm.getDurationSeconds(), nxm.getDurationNanoseconds(),
                nxm.getIdleTimeout(), nxm.getPacketCount(), nxm.getByteCount(),
                tunEntry == null ? 0 : tunEntry.getTunnelId());
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
            int bufferId, boolean sendFlowRemove, boolean checkOverlap,
            boolean emergency, List<OFAction> actions, long matchingTunnelId) {
        log.debug("sendFlowModAdd");
        if (nxm_enabled) {
            sendNxFlowModAdd(
                    MatchTranslation.toNxMatch(match, matchingTunnelId, 0),
                    cookie, idleTimeoutSecs, hardTimeoutSecs, priority,
                    bufferId, sendFlowRemove, checkOverlap, emergency, actions);
            return;
        } else if (matchingTunnelId != 0)
            throw new IllegalArgumentException("Since NXM has not been " +
                    "enabled you cannot match on Tunnel ID.");

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

        try {
            write(fm);
        } catch (IOException e) {
            log.warn("sendFlowModAdd", e);
        }

        // Not sure we need to do this... undo our change.
        match.setWildcards(wc);
    }

    @Override
    public void sendFlowModAdd(OFMatch match, long cookie,
            short idleTimeoutSecs, short hardTimeoutSecs, short priority,
            int bufferId, boolean sendFlowRemove, boolean checkOverlap,
            boolean emergency, List<OFAction> actions) {
        sendFlowModAdd(match, cookie, idleTimeoutSecs, hardTimeoutSecs, priority,
                bufferId, sendFlowRemove, checkOverlap, emergency, actions, 0);
    }

    private void sendNxFlowModAdd(NxMatch match, long cookie,
            short idleTimeoutSecs, short hardTimoutSecs, short priority,
            int bufferId, boolean sendFlowRemove, boolean checkOverlap,
            boolean emergency, List<OFAction> actions) {
        log.debug("sendNxFlowModAdd");

        short flags = 0;

        // Whether to send a OFPT_FLOW_REMOVED message when the flow expires
        // or is deleted.
        if (sendFlowRemove)
            flags |= 1;

        if (checkOverlap)
            flags |= (1 << 1);

        if (emergency)
            flags |= (1 << 2);

        NxFlowMod fm = NxFlowMod.flowModAdd(match, actions,
                bufferId, cookie, priority, flags, idleTimeoutSecs,
                hardTimoutSecs);
        // Need to call prepareSerialize so that the length is pre-computed.
        fm.prepareSerialize();

        log.debug("sendNxFlowModAdd: about to send {}", fm);

        try {
            write(fm);
        } catch (IOException e) {
            log.warn("sendNxFlowModAdd", e);
        }
    }

    @Override
    public void sendFlowModDelete(OFMatch match, boolean strict,
                                  short priority, short outPort) {
        sendFlowModDelete(match, strict, priority, outPort, 0, 0);
    }

    @Override
    public void sendFlowModDelete(OFMatch match, boolean strict, short priority,
            short outPort, long matchingTunnelId, long cookie) {
        log.debug("sendFlowModDelete");
        if (nxm_enabled) {
            sendNxFlowModDelete(
                    MatchTranslation.toNxMatch(match, matchingTunnelId, cookie),
                    strict, priority, outPort);
            return;
        } else {
            if (matchingTunnelId != 0)
                throw new IllegalArgumentException("Since NXM has not been " +
                        "enabled you cannot match on Tunnel ID.");
            if (cookie != 0)
                throw new IllegalArgumentException("Since NXM has not been " +
                        "enabled you cannot match on the Cookie.");
        }

        OFFlowMod fm = (OFFlowMod) factory.getMessage(OFType.FLOW_MOD);
        fm.setCommand(strict ? OFFlowMod.OFPFC_DELETE_STRICT
                : OFFlowMod.OFPFC_DELETE);
        fm.setMatch(match).setPriority(priority).setOutPort(outPort);

        try {
            write(fm);
        } catch (IOException e) {
            log.warn("sendFlowModDelete", e);
        }
    }

    private void sendNxFlowModDelete(NxMatch match, boolean strict,
            short priority, short outPort) {
        log.debug("sendNxFlowModDelete");

        NxFlowMod fm = NxFlowMod.flowModDelete(match, strict, priority,
                outPort);
        fm.prepareSerialize();

        try {
            write(fm);
        } catch (IOException e) {
            log.warn("sendPacketOut", e);
        }
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

        try {
            write(po);
        } catch (IOException e) {
            log.warn("sendPacketOut", e);
        }
    }

    private void setNxmFlowFormat(boolean nxm) {
        log.debug("setNxmFlowFormat");

        NxSetFlowFormat sff = new NxSetFlowFormat(nxm);

        try {
            write(sff);
        } catch (IOException e) {
            log.warn("setNxmFlowFormat", e);
        }
    }

    private void setNxPacketInFormat(boolean nxm) {
        log.debug("setNxPacketInFormat");

        NxSetPacketInFormat spif = new NxSetPacketInFormat(nxm);

        try {
            write(spif);
        } catch (IOException e) {
            log.warn("setNxPacketInFormat", e);
        }
    }

    @Override
    public void enableNxm() {
        nxm_enabled = true;
        setNxmFlowFormat(true);
        setNxPacketInFormat(true);
    }

    @Override
    public void disableNxm() {
        nxm_enabled = false;
        setNxmFlowFormat(false);
        setNxPacketInFormat(false);
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
