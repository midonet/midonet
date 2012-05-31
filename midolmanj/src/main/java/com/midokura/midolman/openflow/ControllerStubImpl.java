/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openflow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

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
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFStatisticsMessageBase;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFVendor;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.statistics.OFAggregateStatisticsRequest;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
import org.openflow.protocol.statistics.OFQueueStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
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
    private final static int POLLING_DEADLINE_MSEC = 300;

    protected Controller controller;

    protected ConcurrentMap<Object, Object> attributes;
    protected Date connectedSince;
    protected OFFeaturesReply featuresReply;
    protected OFGetConfigReply configReply;
    private ConcurrentHashMap<Integer,
        BlockingQueue<OFStatisticsReply>> statsReplies =
            new ConcurrentHashMap<Integer, BlockingQueue<OFStatisticsReply>>();
    protected HashMap<Short, OFPhysicalPort> ports = new HashMap<Short, OFPhysicalPort>();
    private boolean nxm_enabled = false;

    public ControllerStubImpl(SocketChannel sock, Reactor reactor,
            Controller controller) throws IOException {
        super(sock, reactor);

        setController(controller);
    }

    public void start() throws IOException {
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

    private boolean isValidStatsType(OFStatisticsType type) {
        EnumSet statsTypes = EnumSet.allOf(OFStatisticsType.class);
        if (statsTypes.contains(type))
            return true;
        else
            return false;
    }

    private boolean isValidStatsMessage(OFStatisticsMessageBase stats) {
        if (isValidStatsType(stats.getStatisticType()))
            return  true;
        else
            return false;
    }

   @SuppressWarnings("unchecked")
   public OFStatisticsReply getStatisticsReply(int xid) {
       OFStatisticsReply reply = null;
       try {
           log.debug("getStatisticsReply");
           log.debug("statsReplies: {}", statsReplies.toString());
           BlockingQueue<OFStatisticsReply> replyQueue = statsReplies.get(xid);
           log.debug("Waiting for the response...");
           reply = replyQueue.poll(
                   POLLING_DEADLINE_MSEC, TimeUnit.MILLISECONDS);
           log.debug("reply: {}", reply);
           if (reply != null) {
               statsReplies.remove(xid);
               log.debug("Succeeded to retrieve statistics data.");
           }
           return reply;
       } catch (InterruptedException e) {
           log.error("Error on getStatisticsReply: {}", e);
           Thread.currentThread().interrupt();
       }
       log.warn("Failed to convert statistics data due to its invalid type.");
       return reply;
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

    private int sendStatsRequest(final OFStatistics statsRequest,
                                 final OFStatisticsType statsType) {
        List<OFStatistics> statsRequests = new ArrayList<OFStatistics>();

        if (statsType != OFStatisticsType.DESC
                && statsType != OFStatisticsType.TABLE)
            statsRequests.add(statsRequest);
        return sendStatsRequest(statsRequests, statsType);
    }

    private int sendStatsRequest(final List<OFStatistics> statsRequests,
                                 final OFStatisticsType statsType) {
        log.debug("sendStatsRequest");

        if (!isValidStatsType(statsType))
            throw new OpenFlowError("invalid Openflow statistic type request");
        final OFStatisticsRequest request = new OFStatisticsRequest();

        if (statsType != OFStatisticsType.DESC
                && statsType != OFStatisticsType.TABLE) {
            request.setStatistics(statsRequests);
            for (OFStatistics statsRequest: statsRequests)
                request.setLengthU(
                        request.getLengthU() + statsRequest.getLength());
        }

        request.setStatisticType(statsType);
        if (request.getLengthU() < OFStatisticsRequest.MINIMUM_LENGTH)
            throw new OpenFlowError("OFPT_STATS_REQUEST message is too short" +
                    " with length: " + String.valueOf(request.getLengthU()));
        int xid = initiateOperation(
                new SuccessHandler<OFStatisticsReply>() {
                    @Override
                    public void onSuccess(OFStatisticsReply reply) {
                        if (reply.getStatistics().isEmpty())
                            log.debug("No response");
                        BlockingQueue<OFStatisticsReply> replyQueue =
                                        statsReplies.get(reply.getXid());
                        try {
                            replyQueue.put(reply);
                            log.debug("received statistics reply: {}", reply);
                            log.debug("replyQueue: {}, size: {}",
                                    replyQueue, replyQueue.size());
                            log.debug("statsReplies: {}", statsReplies);
                        } catch (InterruptedException e) {
                            log.error("Failed to enqueue statistics reply.");
                            Thread.currentThread().interrupt();
                        }
                    }
                },
                new TimeoutHandler() {
                    @Override
                    public void onTimeout() {
                        log.warn("Retrievineg statistics timed out.");
                        if (socketChannel.isConnected())
                            sendStatsRequest(statsRequests, statsType);
                    }
                }, 3500L, OFType.STATS_REQUEST);
        request.setXid(xid);
        // Initialize the response queue which behaves in the producer-consumer
        // pattern with xid.
        statsReplies.put(xid,
                new ArrayBlockingQueue<OFStatisticsReply>(1));
        log.debug("initiated statistics operation with id: {}",
                request.getXid());
        if (!isValidStatsRequestLength(request, statsRequests.size()))
             throw new OpenFlowError("OFPT_STATS_REQUEST message has invalid" +
                     " length: " + String.valueOf(request.getLengthU()));
        stream.write(request);
        try {
            stream.flush();
        } catch (IOException e) {
            log.warn("sendStatsRequest", e);
        }
        log.debug("sent OFPT_STATS_REQUEST message with length {}.",
                request.getLengthU());
        return xid;
    }

    public int sendDescStatsRequest() {
        log.debug("OFPT_STATS_REQUEST / OFPST_DESC");
        return sendStatsRequest(new ArrayList<OFStatistics>(),
                OFStatisticsType.DESC);
    }

    public int sendFlowStatsRequest(OFMatch match, byte tableId,
                                    short outPort) {
        log.debug("OFPT_STATS_REQUEST / OFPST_FLOW");
        OFFlowStatisticsRequest flowStatsRequest =
                new OFFlowStatisticsRequest();
        flowStatsRequest.setMatch(match);
        flowStatsRequest.setTableId(tableId);
        flowStatsRequest.setOutPort(outPort);
        return sendStatsRequest(flowStatsRequest, OFStatisticsType.FLOW);
    }

    public int sendAggregateStatsRequest(OFMatch match, byte tableId,
                                         short outPort) {
        log.debug("OFPT_STATS_REQUEST / OFPST_AGGREGATE: match={}, " +
                "tableId={], outPort={}",
                new Object[]{match, tableId, outPort});
        OFAggregateStatisticsRequest aggregateStatsRequest =
                new OFAggregateStatisticsRequest();

        aggregateStatsRequest.setMatch(match);
        aggregateStatsRequest.setTableId(tableId);
        aggregateStatsRequest.setOutPort(outPort);
        return sendStatsRequest(
                aggregateStatsRequest, OFStatisticsType.AGGREGATE);
    }

    public int sendTableStatsRequest() {
        log.debug("OFPT_STATS_REQUEST / OFPST_TABLE");
        return sendStatsRequest(new ArrayList<OFStatistics>(),
                OFStatisticsType.TABLE);
    }

    public int sendPortStatsRequest(short portNo) {
        log.debug("OFPT_STATS_REQUEST / OFPST_PORT: portNo={}", portNo);
        OFPortStatisticsRequest portStatsRequest =
                new OFPortStatisticsRequest();

        portStatsRequest.setPortNumber(portNo);
        return sendStatsRequest(portStatsRequest, OFStatisticsType.PORT);
    }

    public int sendQueueStatsRequest(short portNo, int queueId) {
        log.debug("OFPT_STATS_REQUEST / OFPST_QUEUE: portNo={}, queueId={}",
                portNo, queueId);
        OFQueueStatisticsRequest queueStatsRequest =
                new OFQueueStatisticsRequest();

        queueStatsRequest.setPortNumber(portNo);
        queueStatsRequest.setQueueId(queueId);
        return sendStatsRequest(queueStatsRequest, OFStatisticsType.QUEUE);
    }

    @SuppressWarnings("unchecked")
    public int sendQueueStatsRequest(Map<Short, Set<Integer>> queueRequests) {
        String debugString = "";
        for (short portNum : queueRequests.keySet())
            debugString += new StringBuilder().append(" portNo=").append(
                    queueRequests.get(portNum)).append(", queueIds=").append(
                    queueRequests.values());
        log.debug("OFPT_STATS_REQUEST / OFPST_QUEUE: {}", debugString);
        List<OFQueueStatisticsRequest> queueStatsRequests =
                new ArrayList<OFQueueStatisticsRequest>(queueRequests.size());
        for (short portNum: queueRequests.keySet())
            for (int queueNum: queueRequests.get(portNum)) {
                OFQueueStatisticsRequest queueStatsRequest =
                    new OFQueueStatisticsRequest();
                queueStatsRequest.setPortNumber(portNum);
                queueStatsRequest.setQueueId(queueNum);
                queueStatsRequests.add(queueStatsRequest);
            }
        return sendStatsRequest(
                (List) queueStatsRequests, OFStatisticsType.QUEUE);
    }

    protected boolean handleMessage(OFMessage m) throws IOException {
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
            successHandler = terminateOperation(
                    m.getXid(), OFType.STATS_REQUEST);
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
            stream.write(fm);
            stream.flush();
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
            stream.write(fm);
            stream.flush();
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
            stream.write(fm);
            stream.flush();
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
            stream.write(po);
            stream.flush();
        } catch (IOException e) {
            log.warn("sendPacketOut", e);
        }
    }

    private void setNxmFlowFormat(boolean nxm) {
        log.debug("setNxmFlowFormat");

        NxSetFlowFormat sff = new NxSetFlowFormat(nxm);

        try {
            stream.write(sff);
            stream.flush();
        } catch (IOException e) {
            log.warn("setNxmFlowFormat", e);
        }
    }

    private void setNxPacketInFormat(boolean nxm) {
        log.debug("setNxPacketInFormat");

        NxSetPacketInFormat spif = new NxSetPacketInFormat(nxm);

        try {
            stream.write(spif);
            stream.flush();
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
