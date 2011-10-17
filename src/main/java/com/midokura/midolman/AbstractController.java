/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPhysicalPort.OFPortConfig;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.action.OFAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.ReplicatedMap.Watcher;
import com.midokura.midolman.util.Net;

public abstract class AbstractController 
        implements Controller, AbstractControllerMXBean {

    private final static Logger log = 
                        LoggerFactory.getLogger(AbstractController.class);

    protected long datapathId;

    protected ControllerStub controllerStub;

    protected HashMap<UUID, Integer> portUuidToNumberMap;
    protected HashMap<Integer, UUID> portNumToUuid;
    protected PortToIntNwAddrMap portLocMap;

    // Tunnel management data structures
    // private to AbstractController, but publically queriable through
    // tunnelPortNumOfPeer() and peerOfTunnelPortNum().
    private HashMap<Integer, IntIPv4> tunnelPortNumToPeerIp;
    private HashMap<IntIPv4, Integer> peerIpToTunnelPortNum;

    protected PortToIntNwAddrMap.Watcher<UUID, Integer> listener;

    private OpenvSwitchDatabaseConnection ovsdb;

    protected int greKey;
    protected IntIPv4 publicIp;
    protected String externalIdKey;

    public static final short nonePort = OFPort.OFPP_NONE.getValue();
    public static final int portDownFlag =
                                OFPortConfig.OFPPC_PORT_DOWN.getValue();

    protected Set<Integer> downPorts;

    class PortLocMapListener implements Watcher<UUID, Integer> {
        public AbstractController controller;

        PortLocMapListener(AbstractController controller) {
            this.controller = controller;
        }

        public void processChange(UUID key, Integer oldAddr, Integer newAddr) {
            controller.portLocationUpdate(key, oldAddr, newAddr);
        }
    }

    public AbstractController(
            long datapathId,
            UUID switchUuid,
            int greKey,
            OpenvSwitchDatabaseConnection ovsdb,
            PortToIntNwAddrMap portLocMap,
            IntIPv4 internalIp,
            String externalIdKey) {
        this.datapathId = datapathId;
        this.ovsdb = ovsdb;
        this.greKey = greKey;
        this.portLocMap = portLocMap;
        this.externalIdKey = externalIdKey;
        publicIp = internalIp;
        portUuidToNumberMap = new HashMap<UUID, Integer>();
        portNumToUuid = new HashMap<Integer, UUID>();
        tunnelPortNumToPeerIp = new HashMap<Integer, IntIPv4>();
        peerIpToTunnelPortNum = new HashMap<IntIPv4, Integer>();
        downPorts = new HashSet<Integer>();
        listener = new PortLocMapListener(this);
        if (portLocMap != null)
            portLocMap.addWatcher(listener);
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    @Override
    public void onConnectionMade() {
        log.info("onConnectionMade");

        // TODO: Maybe find and record the datapath_id?
        //       The python implementation did, but here we get the dp_id
        //       in the constructor.

        // Delete all currently installed flows.
        OFMatch match = new OFMatch();
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);

        // Add all the non-tunnel ports, delete all the pre-existing tunnel 
        // ports.
        log.debug("onConnectionMade: There are {} pre-existing ports.",
                  controllerStub.getFeatures().getPorts().size());
        for (OFPhysicalPort portDesc : controllerStub.getFeatures()
                                                     .getPorts()) {
            String portName = portDesc.getName();
            log.debug("onConnectionMade: pre-existing port {}", portName);
            if (isGREPortOfKey(portName)) {
                log.info("onConnectionMade: Deleting old tunnel {}", portName);
                log.debug("ovsdb thinks there {} a port {}",
                          ovsdb.hasPort(portName) ? "is" : "is not", portName);
                ovsdb.delPort(portName);
                log.debug("ovsdb thinks there {} a port {}",
                          ovsdb.hasPort(portName) ? "is" : "is not", portName);
            } else {
                if ((portDesc.getConfig() & portDownFlag) == 0)
                    callAddPort(portDesc, portDesc.getPortNumber());
                else
                    downPorts.add(new Integer(portDesc.getPortNumber()));
            }
        }
        log.debug("onConnectionMade: All done handling pre-existing ports.  " +
                  "There are now {} pre-existing ports.",
                  controllerStub.getFeatures().getPorts().size());
                
        portLocMap.start();
    }

    @Override
    public final void onConnectionLost() {
        log.info("onConnectionLost");

        clear();

        portLocMap.stop();

        portNumToUuid.clear();
        portUuidToNumberMap.clear();
        tunnelPortNumToPeerIp.clear();
        peerIpToTunnelPortNum.clear();
        downPorts.clear();
    }

    public abstract void clear();

    @Override
    public abstract void onPacketIn(int bufferId, int totalLen, short inPort,
                                    byte[] data);

    private void callAddPort(OFPhysicalPort portDesc, short portNum) {
        UUID uuid = getPortUuidFromOvsdb(datapathId, portNum);
        log.info("Adding port#{} id: {}", portNum, uuid);
        if (uuid != null) {
            portNumToUuid.put(new Integer(portNum), uuid);
            portUuidToNumberMap.put(uuid, new Integer(portNum));
            if (publicIp != null) {
                try {
                    portLocMap.put(uuid, new Integer(publicIp.address));
                } catch (KeeperException e) {
                    log.warn("callAddPort", e);
                } catch (InterruptedException e) {
                    log.warn("callAddPort", e);
                }
            }
        }
        // TODO(pino, jlm): should this be an else-if?
        if (isGREPortOfKey(portDesc.getName())) {
            IntIPv4 peerIp = peerIpOfGrePortName(portDesc.getName());
            // TODO: Error out if already tunneled to this peer.
            tunnelPortNumToPeerIp.put(new Integer(portNum), peerIp);
            peerIpToTunnelPortNum.put(peerIp, new Integer(portNum));
            log.debug("Recording tunnel {} <=> {}", portNum, peerIp);
        }

        addPort(portDesc, portNum);
    }

    private void handlePortGoingDown(OFPhysicalPort portDesc, Integer portNum) {
        deletePort(portDesc);
        portNumToUuid.remove(portNum);
        portUuidToNumberMap.remove(
                getPortUuidFromOvsdb(datapathId, portNum.shortValue()));
        IntIPv4 peerIp = tunnelPortNumToPeerIp.remove(portNum);

        log.debug("handlePortGoingDown: removing port# {}", portNum);
        peerIpToTunnelPortNum.remove(peerIp);
    }

    @Override
    public final void onPortStatus(OFPhysicalPort portDesc,
                                   OFPortReason reason) {
        log.debug("onPortStatus: portDesc {} reason {}", portDesc, reason);

        if (reason.equals(OFPortReason.OFPPR_ADD)) {
            short portNum = portDesc.getPortNumber();
            if ((portDesc.getConfig() & portDownFlag) == 0)
                callAddPort(portDesc, portNum);
            else
                downPorts.add(new Integer(portNum));
        } else if (reason.equals(OFPortReason.OFPPR_DELETE)) {
            Integer portNum = new Integer(portDesc.getPortNumber());
            if (downPorts.contains(portNum))
                downPorts.remove(portNum);
            else
                handlePortGoingDown(portDesc, portNum);
        } else if (reason.equals(OFPortReason.OFPPR_MODIFY)) {
            Integer portNum = new Integer(portDesc.getPortNumber());
            // Logic for the four up/down states:
            //  * Was down, remains down:  Do nothing.
            //  * Was up, went down:  Treat as delete.
            //  * Was down, came up:  Treat as add.
            //  * Was up, remains up:  Update maps with new data.
            if ((portDesc.getConfig() & portDownFlag) != 0) {
                if (!downPorts.contains(portNum)) {
                    downPorts.add(portNum);
                    handlePortGoingDown(portDesc, portNum);
                }
                return;
            }
            if (downPorts.contains(portNum)) {
                downPorts.remove(portNum);
                callAddPort(portDesc, portNum.shortValue());
                return;
            }
            // Wasn't down, and is still up, so update the state maps.
            UUID uuid = getPortUuidFromOvsdb(datapathId, portNum.shortValue());
            if (uuid != null) {
                portNumToUuid.put(portNum, uuid);
                portUuidToNumberMap.put(uuid, portNum);
            } else
                portNumToUuid.remove(portNum);

            if (isGREPortOfKey(portDesc.getName())) {
                IntIPv4 peerIp = peerIpOfGrePortName(portDesc.getName());
                tunnelPortNumToPeerIp.put(portNum, peerIp);
                peerIpToTunnelPortNum.put(peerIp, portNum);
            } else {
                IntIPv4 peerIp = tunnelPortNumToPeerIp.remove(portNum);
                peerIpToTunnelPortNum.remove(peerIp);
            }
        } else {
            log.error("Unknown OFPortReason update: {}", reason);
        }
        
        log.debug("onPortStatus: peerIpToTunnelPortNum {}", 
                  peerIpToTunnelPortNum);
    }

    protected abstract void addPort(OFPhysicalPort portDesc, short portNum);
    protected abstract void deletePort(OFPhysicalPort portDesc);

    @Override
    public abstract void onFlowRemoved(OFMatch match, long cookie,
            short priority, OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount);

    @Override
    public void onMessage(OFMessage m) {
        log.debug("onMessage: {}", m);
        // Don't do anything else.
    }

    /* Maps a port UUID to its number on the local datapath. */
    public int portUuidToNumber(UUID port_uuid) {
        return portUuidToNumberMap.get(port_uuid);
    }

    /* Maps a remote port UUID to the number of the tunnel port where it
     * can be reached, if any. */
    public Integer portUuidToTunnelPortNumber(UUID port_uuid) {
        Integer intAddress = portLocMap.get(port_uuid);
        if (intAddress == null)
            return null;
        return peerIpToTunnelPortNum.get(new IntIPv4(intAddress));
    }

    public IntIPv4 peerOfTunnelPortNum(int portNum) {
        return tunnelPortNumToPeerIp.get(portNum);
    }

    protected boolean isTunnelPortNum(int portNum) {
        return tunnelPortNumToPeerIp.containsKey(new Integer(portNum));
    }

    private boolean isGREPortOfKey(String portName) {
        if (portName == null || portName.length() != 15)
            return false;
        String greString = String.format("tn%05x", greKey);
        return portName.startsWith(greString);
    }

    protected IntIPv4 peerIpOfGrePortName(String portName) {
        String hexAddress = portName.substring(7, 15);
        return new IntIPv4((new BigInteger(hexAddress, 16)).intValue());
    }

    public String makeGREPortName(IntIPv4 address) {
        return String.format("tn%05x%08x", greKey, address.address);
    }

    private boolean portLocMapContainsPeer(int peerAddress) {
        return portLocMap.containsValue(peerAddress);
    }

    protected UUID getPortUuidFromOvsdb(long datapathId, short portNum) {
        String extId = ovsdb.getPortExternalId(datapathId, portNum,
                                               externalIdKey);
        if (extId == null)
            return null;
        return UUID.fromString(extId);
    }

    private synchronized void portLocationUpdate(UUID portUuid, Integer oldAddr,
                                                 Integer newAddr) {
        /* oldAddr: Former address of the port as an 
         *          integer (32-bit, big-endian); or null if a new port mapping.
         * newAddr: Current address of the port as an
         *          integer (32-bit, big-endian); or null if port mapping 
         *          was deleted.
         */

        IntIPv4 newAddrInt = (newAddr == null) ? null 
                                               : new IntIPv4(newAddr.intValue());
        IntIPv4 oldAddrInt = (oldAddr == null) ? null
                                               : new IntIPv4(oldAddr.intValue());
        log.info("PortLocationUpdate: {} moved from {} to {}",
            new Object[] { portUuid, oldAddrInt, newAddrInt });
        if (newAddrInt != null && !newAddrInt.equals(publicIp)) {
            String grePortName = makeGREPortName(newAddrInt);
            log.info("Requesting tunnel from {} to {} with name {}",
                     new Object[] { publicIp, newAddrInt, grePortName });

            if (publicIp == null) {
                log.error("Trying to make tunnel without a public IP.");
            } else {
                // Only create tunnel if it doesn't already exist.  
                // This won't race with tearing down a tunnel because this
                // method is synchronized.
                if (!ovsdb.hasPort(grePortName)) {
                    ovsdb.addGrePort(datapathId, grePortName,
                                     newAddrInt.toString())
                         .key(greKey)
                         .localIp(publicIp.toString())
                         .build();
                }
            }
        }    

        if (oldAddrInt != null && !oldAddrInt.equals(publicIp)) {
            // Peer might still be in portLocMap under a different portUuid.
            if (!portLocMapContainsPeer(oldAddr)) {
                // Tear down the GRE tunnel.
                String grePortName = makeGREPortName(oldAddrInt);
                log.info("Tearing down tunnel " + grePortName);
                ovsdb.delPort(grePortName);
            }
        }

        portMoved(portUuid, oldAddrInt, newAddrInt);
    }

    abstract protected void portMoved(UUID portUuid, IntIPv4 oldAddr,
                                      IntIPv4 newAddr);

    protected OFMatch createMatchFromPacket(Ethernet data, short inPort) {
        MidoMatch match = new MidoMatch();
        if (inPort != -1)
            match.setInputPort(inPort);
        match.setDataLayerDestination(data.getDestinationMACAddress());
        match.setDataLayerSource(data.getSourceMACAddress());
        match.setDataLayerType(data.getEtherType());
        // See if wildcarding the VLAN fields results in the matches working.
        // match.setDataLayerVirtualLan(data.getVlanID());
        // match.setDataLayerVirtualLanPriorityCodePoint(data.getPriorityCode());
        if (data.getEtherType() == IPv4.ETHERTYPE) {
            IPv4 packet = (IPv4) data.getPayload();
            // Should we wildcard TOS, so that packets differing in TOS
            // are considered part of the same flow?  Going with "yes"
            // for now.
            // match.setNetworkTypeOfService(packet.getDiffServ());
            match.setNetworkProtocol(packet.getProtocol());
            match.setNetworkSource(packet.getSourceAddress());
            match.setNetworkDestination(packet.getDestinationAddress());

            if (packet.getProtocol() == ICMP.PROTOCOL_NUMBER) {
                ICMP dgram = (ICMP) packet.getPayload();
                match.setTransportSource((short) dgram.getType());
                match.setTransportDestination((short) dgram.getCode());
            } else if (packet.getProtocol() == TCP.PROTOCOL_NUMBER) {
                TCP dgram = (TCP) packet.getPayload();
                match.setTransportSource(dgram.getSourcePort());
                match.setTransportDestination(dgram.getDestinationPort());
            } else if (packet.getProtocol() == UDP.PROTOCOL_NUMBER) {
                UDP dgram = (UDP) packet.getPayload();
                match.setTransportSource(dgram.getSourcePort());
                match.setTransportDestination(dgram.getDestinationPort());
            }
        }

        return match;
    }

    protected void addFlowAndPacketOut(OFMatch match, long cookie, 
                short idleTimeout, short hardTimeout, short priority,
                int bufferId, boolean sendFlowRemoval, boolean checkOverlap,
                boolean emergency, OFAction[] actions, short inPort,
                byte[] data) {
        log.debug("addFlowAndPacketOut({} ...)", bufferId);
        List<OFAction> actionList = Arrays.asList(actions);
        controllerStub.sendFlowModAdd(match, cookie, idleTimeout, hardTimeout,
                                      priority, bufferId, sendFlowRemoval,
                                      checkOverlap, emergency, actionList);
        if (bufferId == ControllerStub.UNBUFFERED_ID) {
            log.debug("addFlowAndPacketOut: sending packet");
            controllerStub.sendPacketOut(bufferId, inPort, actionList, data);
        }
    }
    
    public int getGreKey() {
        return greKey;
    }
    
    public Integer tunnelPortNumOfPeer(IntIPv4 peerIP) {
        return peerIpToTunnelPortNum.get(peerIP);
    }
}
