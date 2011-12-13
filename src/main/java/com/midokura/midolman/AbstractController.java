/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.ReplicatedMap.Watcher;

public abstract class AbstractController
        implements Controller {

    private final static Logger log =
                        LoggerFactory.getLogger(AbstractController.class);

    protected long datapathId;

    protected ControllerStub controllerStub;

    protected HashMap<UUID, Integer> portUuidToNumberMap;
    protected HashMap<Integer, UUID> portNumToUuid;
    protected PortToIntNwAddrMap portLocMap;

    // Tunnel management data structures
    // private to AbstractController, but publicly queriable through
    // tunnelPortNumOfPeer() and peerOfTunnelPortNum().
    private HashMap<Integer, IntIPv4> tunnelPortNumToPeerIp;
    private HashMap<IntIPv4, Integer> peerIpToTunnelPortNum;

    protected PortToIntNwAddrMap.Watcher<UUID, IntIPv4> listener;

    private OpenvSwitchDatabaseConnection ovsdb;

    protected int greKey;
    protected IntIPv4 publicIp;
    protected String externalIdKey;

    public static final short nonePort = OFPort.OFPP_NONE.getValue();
    public static final int portDownFlag =
                                OFPortConfig.OFPPC_PORT_DOWN.getValue();

    protected Set<Integer> downPorts;

    class PortLocMapListener implements Watcher<UUID, IntIPv4> {
        public AbstractController controller;

        PortLocMapListener(AbstractController controller) {
            this.controller = controller;
        }

        public void processChange(UUID key, IntIPv4 oldAddr, IntIPv4 newAddr) {
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
                onPortStatus(portDesc, OFPortReason.OFPPR_ADD);
            }
        }
        log.debug("onConnectionMade: All done handling pre-existing ports.");
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

    private void _addVirtualPort(int num, String name, MAC addr, UUID uuid) {
        log.info("_addVirtualPort num:{} name:{} addr:{} id:{}",
                new Object[] { num, name, addr, uuid });
        portNumToUuid.put(num, uuid);
        portUuidToNumberMap.put(uuid, num);
        try {
            portLocMap.put(uuid, publicIp);
        } catch (KeeperException e) {
            log.warn("callAddPort", e);
        } catch (InterruptedException e) {
            log.warn("callAddPort", e);
        }
        addVirtualPort(num, name, addr, uuid);
    }

    private void _addTunnelPort(int portNum, IntIPv4 peerIp) {
        // TODO: Error out if already tunneled to this peer.
        tunnelPortNumToPeerIp.put(new Integer(portNum), peerIp);
        peerIpToTunnelPortNum.put(peerIp, new Integer(portNum));
        log.debug("Recording tunnel {} <=> {}", portNum, peerIp);
        addTunnelPort(portNum, peerIp);
    }

    private void _deleteVirtualPort(int portNum, UUID uuid) {
        log.info("_deleteVirtualPort num:{} id:{}", portNum, uuid);
        // First notify the subclass then update the maps.
        deleteVirtualPort(portNum, uuid);
        portNumToUuid.remove(portNum);
        portUuidToNumberMap.remove(uuid);
        try {
            portLocMap.remove(uuid);
        } catch (KeeperException e) {
            log.warn("callAddPort", e);
        } catch (InterruptedException e) {
            log.warn("callAddPort", e);
        }
    }

    private void _deleteTunnelPort(int portNum) {
        IntIPv4 peerIp = tunnelPortNumToPeerIp.get(portNum);
        log.info("_deleteTunnelPort num:{} to peer:{}", portNum, peerIp);
        // First notify the subclass then update the maps.
        deleteTunnelPort(portNum, peerIp);
        tunnelPortNumToPeerIp.remove(portNum);
        peerIpToTunnelPortNum.remove(peerIp);
    }

    @Override
    public final void onPortStatus(OFPhysicalPort portDesc,
                                   OFPortReason reason) {
        int portNum = portDesc.getPortNumber() & 0xffff;
        String name = portDesc.getName();
        MAC addr = new MAC(portDesc.getHardwareAddress());
        log.info("onPortStatus: num:{} name:{} reason:{}",
                new Object[] { portNum, name, reason });

        // OpenFlow ports (ports on the OF-compliant switch) are used in one of
        // three ways:
        //
        // 1) Virtual ports - the OF port maps to a port in the virtual
        // topology. Any packet that is received by the OF port is considered
        // to be received by the virtual port and therefore enters a virtual
        // device. Virtual ports can be recognized thanks to an 'externalId'
        // in the Open vSwitch configuration whose key is 'midolman-vnet' and
        // whose value is a UUID.
        //
        // 2) Tunnel ports - the OF port is the switch's tunnel (currently GRE
        // only) to one remote server. The remote server address is encoded in
        // the tunnel port's name. The tunnel must connect to another OF switch
        // (OVS datapath) that has the same externalId (e.g. by using the same
        // GRE key). Tunnel ports are recognized by their naming pattern and
        // the lack of any 'externalId'.
        //
        // 3)Service ports - the OF port is used by a process on the host OS
        // to implement a protocol or feature (e.g BGP). The port is managed by
        // a PortService and has no equivalence in the virtual topology. Think
        // of the port as being part of the internal software of one of the
        // virtual devices. Service ports are recognized thanks to an OVS
        // 'externalId' whose key is 'midolman_port_id' and whose value is a
        // virtual port UUID.
        // 
        // OF ports that are not recognized as one of these three types are
        // ignored by the controller and packets received on them are dropped.

        // Does it have a virtual port external id in OVSDB?
        UUID uuid = getPortUuidFromOvsdb(datapathId, portNum);
        // Does its name match the tunnel naming pattern?
        IntIPv4 peerIp = null;
        if (isGREPortOfKey(name))
            peerIp = peerIpOfGrePortName(name);
        // Does it have a service port external id in OVSDB?
        UUID svcId = getServicePortUuidFromOvsdb(datapathId, portNum);

        // The three port types are mutually exclusive
        if (null != uuid && peerIp != null)
            log.error("onPortStatus num:{} seems to be a tunnel to {} but "
                    + "has a virtual port id {}",
                    new Object[] { portNum, peerIp, uuid });
        if (null != uuid && null != svcId)
            log.error("onPortStatus num:{} has both a virtual port id {} "
                    + "and a service port id {}",
                    new Object[] { portNum, uuid, svcId });
        if (null != svcId && peerIp != null)
            log.error("onPortStatus num:{} seems to be a tunnel to {} but "
                    + "has a service port uuid {}",
                    new Object[] { portNum, peerIp, svcId });

        if (reason.equals(OFPortReason.OFPPR_ADD)) {
            boolean portDown = (portDesc.getConfig() & portDownFlag) != 0;
            // If it's a service port - don't care whether it's up.
            if (null != svcId)
                addServicePort(portNum, name, svcId);
            else if (portDown) {
                log.info("onPortStatus num:{} is down, don't notify subclass",
                        portNum);
                downPorts.add(portNum);
            } else if (null != uuid)
                _addVirtualPort(portNum, name, addr, uuid);
            else if (peerIp != null)
                _addTunnelPort(portNum, peerIp);
            else
                log.error("onPortStatus unrecognized port type - not service"
                        + "port, nor virtual port, nor tunnel");
        } else if(reason.equals(OFPortReason.OFPPR_DELETE)) {
            // Remove any flows which have this port as the in-port.
            deleteFlowsByInPort(portNum);
            if (null != svcId)
                deleteServicePort(portNum, name, svcId);
            // It's a tunnel or virtual port.
            else if (downPorts.contains(portNum))
                downPorts.remove(portNum);
            else if (null != uuid)
                _deleteVirtualPort(portNum, uuid);
            else if (peerIp != null)
                _deleteTunnelPort(portNum);
            else
                log.error("onPortStatus unrecognized port type - not service"
                        + "port, nor virtual port, nor tunnel");
        } else if (reason.equals(OFPortReason.OFPPR_MODIFY)) {
            // If it's a service port do nothing.
            // TODO(pino, yoshi): handle service port changes.
            if (null != svcId) {
                return;
            }
            // It's a tunnel or virtual port.
            boolean portDown = (portDesc.getConfig() & portDownFlag) != 0;
            // Logic for the four up/down states:
            // * Was down, remains down:  Do nothing.
            // * Was up, went down:  Treat as delete.
            // * Was down, came up:  Treat as add.
            // * Was up, remains up:  Update maps with new data.
            if (portDown) {
                if (!downPorts.contains(portNum)) {
                    // * Was up, went down:  Treat as delete.
                    downPorts.add(portNum);
                    if (null != uuid)
                        _deleteVirtualPort(portNum, uuid);
                    else if(peerIp != null)
                        _deleteTunnelPort(portNum);
                } // else * Was down, remains down:  Do nothing.
                return;
            }
            if (downPorts.contains(portNum)) {
                // * Was down, came up:  Treat as add.
                downPorts.remove(portNum);
                if (null != uuid)
                    _addVirtualPort(portNum, name, addr, uuid);
                else if(peerIp != null)
                    _addTunnelPort(portNum, peerIp);
                return;
            }
            // * Was up, remains up:  Update maps with new data.
            // Was the port's uuid changed or removed?
            UUID oldId = portNumToUuid.get(portNum);
            if (null != oldId && !oldId.equals(uuid))
                _deleteVirtualPort(portNum, oldId);
            // Does the port have a new or different uuid?
            if (uuid != null && !uuid.equals(oldId))
                _addVirtualPort(portNum, name, addr, uuid);

            // Was the port previously a tunnel port?
            IntIPv4 oldPeerIp = tunnelPortNumToPeerIp.get(portNum);
            if (null != oldPeerIp && !oldPeerIp.equals(peerIp))
                _deleteTunnelPort(portNum);
            // Is the port a new tunnel or did its name change?
            if (peerIp != null && !peerIp.equals(oldPeerIp))
                _addTunnelPort(portNum, peerIp);
        } else {
            log.error("Unknown OFPortReason update: {}", reason);
        }
    }

    /**
     * Virtual ports are added only if they are actually up.
     * @param num
     * @param name
     * @param addr
     * @param vId
     */
    protected abstract void addVirtualPort(int num, String name, MAC addr,
            UUID vId);
    protected abstract void deleteVirtualPort(int num, UUID vId);

    /**
     * Service ports are added regardless of whether they are up.
     * @param num
     * @param name
     * @param vId
     */
    protected abstract void addServicePort(int num, String name, UUID vId);
    protected abstract void deleteServicePort(int num, String name, UUID vId);

    /**
     * Tunnel ports are added only if they are actually up.
     * @param num
     * @param peerIP
     */
    protected abstract void addTunnelPort(int num, IntIPv4 peerIP);
    protected abstract void deleteTunnelPort(int num, IntIPv4 peerIP);

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

    /* Maps a remote port UUID to the number of the tunnel port where it
     * can be reached, if any. */
    public Integer portUuidToTunnelPortNumber(UUID port_uuid) {
        IntIPv4 intAddress = portLocMap.get(port_uuid);
        if (intAddress == null)
            return null;
        return peerIpToTunnelPortNum.get(intAddress);
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

    private boolean portLocMapContainsPeer(IntIPv4 peerAddress) {
        return portLocMap.containsValue(peerAddress);
    }

    protected UUID getPortUuidFromOvsdb(long datapathId, int portNum) {
        String extId = ovsdb.getPortExternalId(datapathId, portNum,
                                               externalIdKey);
        return (extId == null) ? null : UUID.fromString(extId);
    }

    protected UUID getServicePortUuidFromOvsdb(long datapathId, int portNum) {
        String extId = ovsdb.getPortExternalId(datapathId, portNum,
                "midolman_port_id");
        return (extId == null) ? null : UUID.fromString(extId);
    }

    private synchronized void portLocationUpdate(UUID portUuid, IntIPv4 oldAddr,
                                                 IntIPv4 newAddr) {
        /* oldAddr: Former address of the port as an IntIPv4;
         *          or null if a new port mapping.
         * newAddr: Current address of the port as an IntIPv4;
         *          or null if port mapping was deleted.
         */

        log.info("PortLocationUpdate: {} moved from {} to {}",
                new Object[] { portUuid, oldAddr, newAddr });
        if (newAddr != null && !newAddr.equals(publicIp)) {
            String grePortName = makeGREPortName(newAddr);
            log.info("Requesting tunnel from {} to {} with name {}",
                    new Object[] { publicIp, newAddr, grePortName });

            if (publicIp == null) {
                log.error("Trying to make tunnel without a public IP.");
            } else {
                // Only create tunnel if it doesn't already exist.
                // This won't race with tearing down a tunnel because this
                // method is synchronized.
                if (!ovsdb.hasPort(grePortName)) {
                    ovsdb.addGrePort(datapathId, grePortName,
                                     newAddr.toString())
                         .key(greKey)
                         .localIp(publicIp.toString())
                         .build();
                }
            }
        }

        if (oldAddr != null && !oldAddr.equals(publicIp)) {
            // Peer might still be in portLocMap under a different portUuid.
            if (!portLocMapContainsPeer(oldAddr)) {
                // Tear down the GRE tunnel.
                String grePortName = makeGREPortName(oldAddr);
                log.info("Tearing down tunnel " + grePortName);
                ovsdb.delPort(grePortName);
            }
        }

        portMoved(portUuid, oldAddr, newAddr);
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

    protected void deleteFlowsByInPort(int portNum) {
        MidoMatch match = new MidoMatch();
        match.setInputPort((short) portNum);
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
    }
}
