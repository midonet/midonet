package com.midokura.midolman;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionDataLayer;
import org.openflow.protocol.action.OFActionDataLayerDestination;
import org.openflow.protocol.action.OFActionDataLayerSource;
import org.openflow.protocol.action.OFActionNetworkLayerAddress;
import org.openflow.protocol.action.OFActionNetworkLayerDestination;
import org.openflow.protocol.action.OFActionNetworkLayerSource;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionTransportLayer;
import org.openflow.protocol.action.OFActionTransportLayerDestination;
import org.openflow.protocol.action.OFActionTransportLayerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.actors.threadpool.Arrays;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.Network;
import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.layer3.Router.Action;
import com.midokura.midolman.layer3.Router.ForwardInfo;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortLocationMap;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.util.Callback;

public class NetworkController extends AbstractController {

    // This contains all the state needed to pause/resume processing for a packet.
    // buf_id, in_port, pkt, and orig_match are needed to install new rules after
    // processing has finished. The diff between orig_match and last_match
    // determines the field-rewriting anctions to take on matched packets.
    // The ingress_port_uuid is the last forwarding element's ingress port and the
    // source for any ICMPs that may be triggered. The egress_port_uuid is the
    // materialized port that will emit the packet. The egress_gw_ip is the
    // intermediate gateway's ip address whose mac address will be the dl_dst of
    // the emitted packet, or None if the nw_dst is directly reachable from the
    // port.
    // routers is a list of the virtual routers traversed by the packet.
    // fwd_action is the action returned by the VRN, may not always be set.
    private class PacketContext implements Callback<byte[]> {
        int bufferId;
        short inPortNum;
        UUID inPortId;
        Ethernet ethPkt;
        MidoMatch origMatch;
        MidoMatch lastMatch;
        UUID lastInPortId;
        UUID lastEgPortId;
        int gwNwAddr;
        byte[] gwDlAddr;
        short outPortNum;
        Collection<UUID> routers;
        Action fwdAction;

        public PacketContext(int bufferId, short inPortNum, UUID inPortId,
                Ethernet ethPkt, MidoMatch origMatch, MidoMatch lastMatch,
                UUID lastInPortId, UUID lastEgPortId, int gwNwAddr, byte[] gwDlAddr,
                short outPortNum, Collection<UUID> routers, Action fwdAction) {
            this.bufferId = bufferId;
            this.inPortNum = inPortNum;
            this.inPortId = inPortId;
            this.ethPkt = ethPkt;
            this.origMatch = origMatch;
            this.lastMatch = lastMatch;
            this.lastInPortId = lastInPortId;
            this.lastEgPortId = lastEgPortId;
            this.gwNwAddr = gwNwAddr;
            this.gwDlAddr = gwDlAddr;
            this.outPortNum = outPortNum;
            this.routers = routers;
            this.fwdAction = fwdAction;
        }

        @Override
        public void call(byte[] mac) {
            gwDlAddr = mac;
            arpCompleted(this);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(NetworkController.class);
    // TODO(pino): This constant should be declared in openflow...
    private static final short OFP_FLOW_PERMANENT = 0;
    private static final short ICMP_EXPIRY_SECONDS = 5;
    private static final short IDLE_TIMEOUT_SECS = 0;
    private static final short FLOW_PRIORITY = 0;

    private PortDirectory portDir;
    private Network network;
    private Map<UUID, L3DevicePort> devPortById;
    private Map<Short, L3DevicePort> devPortByNum;
    private Reactor reactor;

    public NetworkController(int datapathId, UUID deviceId, int greKey,
            PortLocationMap dict, long flowExpireMinMillis,
            long flowExpireMaxMillis, long idleFlowExpireMillis,
            InetAddress internalIp, RouterDirectory routerDir, 
            PortDirectory portDir, Reactor reactor) {
        super(datapathId, deviceId, greKey, dict, flowExpireMinMillis,
                flowExpireMaxMillis, idleFlowExpireMillis, internalIp);
        // TODO Auto-generated constructor stub
        this.portDir = portDir;
        this.network = new Network(deviceId, routerDir, portDir, reactor);
        this.reactor = reactor;
        this.devPortById = new HashMap<UUID, L3DevicePort>();
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        MidoMatch pktMatch = new MidoMatch();
        pktMatch.loadFromPacket(data, inPort);
        Ethernet ethPkt = new Ethernet();
        ethPkt.deserialize(data, 0, data.length);
        // Note that we set orig_match and last_match equal here. When looping
        // through a chain of forwarding elements, orig_match never changes, and
        // last_match is the output of the last forwarding element and the input
        // to the next forwarding element.
        PacketContext pktCtx = new PacketContext(bufferId, inPort, null,
                ethPkt, pktMatch, null, null, null, 0, null, (short)0, null, null);

        boolean portIsTunnel = false; // TODO: call super.portIsTunnel(inPort);
        if (portIsTunnel) {
            processForwardedPacket(pktCtx);
            return;
        }
        // It's a packet from a materialized port.
        L3DevicePort devPort = devPortByNum.get(inPort);
        if (null == devPort){
            // drop packets entering on ports that we don't recognize.
            // TODO: free the buffer.
            return;
        }
        pktCtx.inPortId = devPort.getId();
        pktCtx.lastInPortId = devPort.getId();
        try {
            processPacket(pktCtx);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (KeeperException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void processPacket(PacketContext pktCtx) throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        ForwardInfo fwdInfo = new ForwardInfo(pktCtx.inPortId, null, 0, null,
                false);
        Set<UUID> routers = new HashSet<UUID>();
        Action action = network.process(pktCtx.origMatch, pktCtx.ethPkt,
                fwdInfo, routers);
        pktCtx.inPortId = fwdInfo.inPortId;
        boolean useWildcards = true; // TODO(pino): replace with real config.
        MidoMatch flowMatch = pktCtx.origMatch;
        if (action.equals(Action.BLACKHOLE)) {
            // TODO(pino): the following wildcarding seems too aggressive.
            // If wildcards are enabled, wildcard everything but nw_src and nw_dst.
            // This is meant to protect against DOS attacks by preventing ipc's to
            // the Openfaucet controller if mac addresses or tcp ports are cycled.
            if (useWildcards)
                flowMatch = makeWildcarded(flowMatch);
            installBlackhole(flowMatch, pktCtx.bufferId, OFP_FLOW_PERMANENT);
            // TODO(pino): notify flow checker about this flow rule.
            notifyFlowAdded(pktCtx.origMatch, flowMatch, pktCtx.inPortId,
                    pktCtx.lastInPortId, fwdInfo, routers);
        }
        else if(action.equals(Action.CONSUMED)) {
            // Free the buffer.
            freeBuffer(pktCtx);
        }
        else if(action.equals(Action.FORWARD)) {
            pktCtx.lastEgPortId = fwdInfo.outPortId;
            pktCtx.gwNwAddr = fwdInfo.gatewayNwAddr;
            pktCtx.lastMatch = fwdInfo.newMatch;
            pktCtx.routers = routers;
            forwardPacketUsingPortId(pktCtx);
        }
        else if(action.equals(Action.NOT_IPV4)) {
            // If wildcards are enabled, wildcard everything but dl_type. One
            // rule per ethernet protocol type catches all non-IPv4 flows.
            if (useWildcards) {
                short dlType = flowMatch.getDataLayerType();
                flowMatch = new MidoMatch();
                flowMatch.setDataLayerType(dlType);
            }
            installBlackhole(flowMatch, pktCtx.bufferId, OFP_FLOW_PERMANENT);
            // Don't notify the flow checker about this rule.
        }
        else if(action.equals(Action.NO_ROUTE)) {
            // Intentionally use an exact match for this drop rule.
            installBlackhole(flowMatch, pktCtx.bufferId, ICMP_EXPIRY_SECONDS);
            // Send an ICMP
            sendICMP(ICMP.CODE_UNREACH_NET, pktCtx.ethPkt, pktCtx.inPortId,
                    pktCtx.lastInPortId);
            // This rule is temporary, don't notify the flow checker.
        }
        else if(action.equals(Action.REJECT)) {
            // Intentionally use an exact match for this drop rule.
            installBlackhole(flowMatch, pktCtx.bufferId, ICMP_EXPIRY_SECONDS);
            // Send an ICMP
            sendICMP(ICMP.CODE_UNREACH_FILTER_PROHIB, pktCtx.ethPkt, pktCtx.inPortId,
                    pktCtx.lastInPortId);
            // This rule is temporary, don't notify the flow checker.
        }
        else {
            throw new RuntimeException("Unrecognized forwarding Action type.");
        }
    }

    private void forwardPacketUsingPortId(PacketContext pktCtx) {
        // First check whether the egress port is local.
        L3DevicePort devPort = devPortById.get(pktCtx.lastEgPortId);
        if (null == devPort) {
            // The egress port is remote. Encode the ingress and egress port
            // uuid's and the gwNwAddr in the last match's mac src and dst.
            // Note that the encoded ingress port is that of the port on the
            // router that owns the egress port (for ICMP correctness).
            setDlHeadersForTunnel(pktCtx.lastMatch, pktCtx.lastInPortId,
                    pktCtx.lastEgPortId, pktCtx.gwNwAddr);
            // TODO: Find the number of the tunnel to this remote port.
            int peerAddr = 0; // TODO: get from PortLocationMap(lastEgPortId)
            short tunNum = 0; // TODO: implement getTunPortNum(peerAddr);
            if (-1 == tunNum) {
                // Couldn't find a tunnel to this egress port.
                // TODO: log
                // Don't need to notify flow checker about this temp rule.
                installBlackhole(pktCtx.origMatch, pktCtx.bufferId,
                        ICMP_EXPIRY_SECONDS);
                // TODO: check whether this is the right error code (host?).
                sendICMP(ICMP.CODE_UNREACH_NET, pktCtx.ethPkt, pktCtx.inPortId,
                        pktCtx.lastInPortId);
            }
            else {
                pktCtx.outPortNum = tunNum;
                setupFlowToPort(pktCtx);
            }
        }
        else {
            pktCtx.outPortNum = devPort.getNum();
            // Get the mac address. The PacketContext is the async callback.
            pktCtx.gwDlAddr = network.getMacForIp(pktCtx.lastEgPortId, pktCtx.gwNwAddr,
                    pktCtx);
            if (null != pktCtx.gwDlAddr)
                arpCompleted(pktCtx);
            // else, the arp will be completed asynchronously by the callback.
        }
    }

    private void arpCompleted(PacketContext pktCtx) {
        if (null == pktCtx.gwDlAddr) {
            installBlackhole(pktCtx.origMatch, pktCtx.bufferId, ICMP_EXPIRY_SECONDS);
            // No need to notify the flow checker, this rule expires soon.
            // Send an ICMP !H
            if (null == pktCtx.inPortId) {
                // The packet came over a tunnel, so we don't know the original
                // in_port_uuid. Also, since the ethernet addresses of tunneled packets
                // are encoded port_uuids and next hop gateway, they can happen to be
                // multicast/broadcast, which fail to generate ICMPs. Hence we change
                // them to unicast addresses.
                pktCtx.ethPkt.setSourceMACAddress(
                        new byte[] {(byte)0x02, (byte)0xa1, (byte)0xb2, (byte)0xc3,
                                (byte)0xd4, (byte)0xe5});
                pktCtx.ethPkt.setDestinationMACAddress(
                        new byte[] {(byte)0x02, (byte)0xa1, (byte)0xb2, (byte)0xc3,
                                (byte)0xd4, (byte)0xe5});
            }
            sendICMP(ICMP.CODE_UNREACH_HOST, pktCtx.ethPkt, pktCtx.inPortId,
                    pktCtx.lastInPortId);
        }
        else {
            L3DevicePort devPort = devPortById.get(pktCtx.lastEgPortId);
            if (null == devPort)
                // The port was removed while we waited for the ARP.
                return;
            pktCtx.lastMatch.setDataLayerSource(devPort.getMacAddr());
            pktCtx.lastMatch.setDataLayerDestination(pktCtx.gwDlAddr);
            setupFlowToPort(pktCtx);
        }
    }

    private void setupFlowToPort(PacketContext pktCtx) {
        // Create OF actions for fields that changed from original to last
        // match. Then install the flow and send the packet.
        MidoMatch m1 = pktCtx.origMatch;
        MidoMatch m2 = pktCtx.lastMatch;
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction action = null;
        if(!Arrays.equals(m1.getDataLayerSource(), m2.getDataLayerSource())) {
            action = new OFActionDataLayerSource();
            ((OFActionDataLayer)action).setDataLayerAddress(
                    m2.getDataLayerSource());
            actions.add(action);
        }
        if(!Arrays.equals(m1.getDataLayerDestination(),
                m2.getDataLayerDestination())) {
            action = new OFActionDataLayerDestination();
            ((OFActionDataLayer)action).setDataLayerAddress(
                    m2.getDataLayerDestination());
            actions.add(action);
        }
        if(m1.getNetworkSource() != m2.getNetworkSource()){
            action = new OFActionNetworkLayerSource();
            ((OFActionNetworkLayerAddress)action).setNetworkAddress(
                    m2.getNetworkSource());
            actions.add(action);
        }
        if(m1.getNetworkDestination() != m2.getNetworkDestination()){
            action = new OFActionNetworkLayerDestination();
            ((OFActionNetworkLayerAddress)action).setNetworkAddress(
                    m2.getNetworkDestination());
            actions.add(action);
        }
        if(m1.getTransportSource() != m2.getTransportSource()){
            action = new OFActionTransportLayerSource();
            ((OFActionTransportLayer)action).setTransportPort(
                    m2.getTransportSource());
            actions.add(action);
        }
        if(m1.getTransportDestination() != m2.getTransportDestination()){
            action = new OFActionTransportLayerDestination();
            ((OFActionTransportLayer)action).setTransportPort(
                    m2.getTransportDestination());
            actions.add(action);
        }
        action = new OFActionOutput(pktCtx.outPortNum, (short) 0);
        boolean useWildcards = true;
        if (useWildcards) {
            // TODO: Should we check for non-load-balanced routes and wild-card
            // flows matching them on layer 3 and lower? inPort, dlType, nwSrc.
            if (null != pktCtx.inPortId)
                m1 = makeWildcarded(m1);
            else
                m1 = makeWildcardedFromTunnel(m1);
            
        }
        // TODO(pino): refactor sendFlowModAdd into several commands in order to 
        // avoid verbose calls like this one.
        super.controllerStub.sendFlowModAdd(m1, 0, IDLE_TIMEOUT_SECS, FLOW_PRIORITY,
                pktCtx.bufferId, true, false, false, actions, (short)0);
        if (null != pktCtx.inPortId) {
            // The packet came from a local materialized port, so we have to
            // notify the flow checker about this rule.
            ; // TODO
        }
    }

    private MidoMatch makeWildcardedFromTunnel(MidoMatch m1) {
        // TODO Auto-generated method stub
        return null;
    }

    public static void setDlHeadersForTunnel(MidoMatch lastMatch, UUID lastInPortId,
            UUID lastEgPortId, int gwNwAddr) {
        // Set the data layer source and destination:
        // The ingress port is used as the high 32 bits of the source mac.
        // The egress port is used as the low 32 bits of the dst mac.
        // The high 16 bits of the gwNwAddr are the low 16 bits of the src mac.
        // The low 16 bits of the gwNwAddr are the high 16 bits of the dst mac.
        byte[] src = new byte[6];
        byte[] dst = new byte[6];
        int ingress = PortDirectory.UUID32toInt(lastInPortId);
        for (int i = 0; i < 4; i++)
            src[i] = (byte)(ingress >> (3-i)*8);
        src[4] = (byte)(gwNwAddr >> 24);
        src[5] = (byte)(gwNwAddr >> 16);
        dst[0] = (byte)(gwNwAddr >> 8);
        dst[1] = (byte)(gwNwAddr);
        int egress = PortDirectory.UUID32toInt(lastEgPortId);
        for (int i = 2; i < 6; i++)
            src[i] = (byte)(egress >> (5-i)*8);
        lastMatch.setDataLayerSource(src);
        lastMatch.setDataLayerDestination(dst);
    }

    public static UUID ingressUUIDFromDlHeaders(final byte[] src, final byte[] dst) {
        int ingress = 0;
        for (int i = 0; i < 4; i++)
            ingress |= src[i] << (3-i)*8;
        return PortDirectory.intTo32BitUUID(ingress);
    }

    public static UUID egressUUIDFromDlHeaders(final byte[] src, final byte[] dst) {
        int egress = 0;
        for (int i = 2; i < 6; i++)
            egress |= dst[i] << (5-i)*8;
        return PortDirectory.intTo32BitUUID(egress);
    }

    public static int gatewayNwAddrFromDlHeaders(final byte[] src, final byte[] dst) {
        int nwAddr = src[4] << 24;
        nwAddr |= src[5] << 16;
        nwAddr |= dst[0] << 8;
        nwAddr |= dst[1];
        return nwAddr;
    }

    private void sendICMP(char codeUnreachNet, Ethernet ethPkt, UUID firstInPortId,
            UUID lastInPortId) {
        // Certain packets shouldn't result in ICMP Unreachable.
        // Per RFC 1812 sec. 4.3.2.7, these are:
        //   * Other ICMP Unreachable.
        //   * Invalid IP packets.
        //   * L2 or L3 src or dest address is multicast or broadcast.
        //   * Second and later IP fragments.
        if (ethPkt.getEtherType() != IPv4.ETHERTYPE)
            return;
        IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
        if(ipPkt.getProtocol() == ICMP.PROTOCOL_NUMBER) {
            ICMP icmpPkt = ICMP.class.cast(ipPkt.getPayload());
            if(icmpPkt.getType() == ICMP.TYPE_UNREACH){
                log.info("Don't generate ICMP Unreachable for other ICMP " +
                        "Unreachable.");
                return;
            }
        }
        /*
        genippkt = pkt_data.ip
                if (genippkt.p == ip.IP_PROTO_ICMP and
                    genippkt.icmp.type == icmp.ICMP_UNREACH):
                  logging.info("Don't generate ICMP Unreachable for other ICMP "
                               "Unreachable")
                  return
                if (is_mcast_ip(genippkt.src) or is_mcast_ip(genippkt.dst) or
                    is_mcast_eth(pkt_data.src) or is_mcast_eth(pkt_data.dst)):
                  logging.info("Don't generate ICMP Unreachable for packets with L2 or L3 "
                               "src or dst **cast addresses")
                  return
                if (genippkt.off & 0x1fff) != 0:
                  logging.info("Don't generate ICMP Unreachable for IP fragment packet")
                  return
                if (genippkt.src == b'\xFF\xFF\xFF\xFF' or
                    genippkt.dst == b'\xFF\xFF\xFF\xFF'):
                  logging.info("Don't generate ICMP Unreachable for all-hosts broadcast "
                               "packet")
                  return
                // Get local-subnet bcast addrs from the out_port to verify trigger pkt's
                // addresses aren't local subnet bcast.
                (ptype, pconfig) = \
                            self._port_dir.get_port_configuration(out_port_uuid)
                if ptype != port.ROUTER_PORT and ptype != port.LOGICAL_ROUTER_PORT:
                  message = 'Expected a router port but got a %s' % ptype
                  logging.error(message)
                  raise Exception, message
                // TODO(pino): if the port is logical, should we do the VRN routing here
                // so that we compute the localnet_bcast address for the materialized
                // final out_port?
                localnet_bcast = broadcast_addr_of_subnet(
                    socket.inet_pton(socket.AF_INET, pconfig.nw_prefix[0]),
                    pconfig.nw_prefix[1])
                // TODO(pino): does it matter if the out_port_uuid is the triggering
                // packet's ingress port to the VRN or to the FE?
                if genippkt.src == localnet_bcast or genippkt.dst == localnet_bcast:
                  logging.info('Dont generate ICMP Unreachable for packets whose src or '
                               'dst is a local subnet broadcast')
                  return

                logging.debug("Generating ICMP Unreachable in response to packet: %s / %s"
                              % (repr(genippkt), b2a_hex(str(genippkt))))
                // Return the IP header and first 8 bytes of the generating packet.
                genippkt = pkt_data.ip
                ihl = (genippkt.v_hl & 0xf) << 2;
                icmppkt = icmp.ICMP(type=icmp.ICMP_UNREACH, code=code,
                                    data='\0\0\0\0'+str(genippkt)[:ihl+8])
                // NOTE: we use the triggering port's ip address as the icmp's source.
                ipsrc = self.localgw_for_port(trigger_port_uuid)
                logging.debug('Using %s as the source of the icmp' %
                              socket.inet_ntoa(ipsrc))
                ippkt = ip.IP(src=ipsrc, dst=genippkt.src, p=ip.IP_PROTO_ICMP,
                              data=icmppkt)
                ippkt.len += len(icmppkt)

                // If the out_port is logical, we need to find the materialized port on
                // which it should be emitted.
                if ptype == port.LOGICAL_ROUTER_PORT:
                  // Need a bogus ethernet packet to make the match.
                  _pkt = ethernet.Ethernet(data=ippkt, type=ethernet.ETH_TYPE_IP,
                      src=b'\x00\xa1\xb2\xc3\xd4\xe5', dst=b'\x00\xa1\xb2\xc3\xd4\xe5')
                  _pkt.unpack(str(_pkt))
                  match = self.create_match_from_packet(_pkt, in_port=0)
                  fwd_action, _, _ = self._vrn.vrn_process_packet_match(match,
                                                                     pconfig.peer_uuid)
                  if fwd_action.type != forwarding_element.OUTPUT:
                      logging.warn("Dropping ICMP error message. Expected action "
                                   "'output' but got %s" % fwd_action.type)
                      return
                  out_port_uuid = fwd_action.args.out_port_uuid
                  match = fwd_action.args.new_match
                  ptype, pconfig = self._port_dir.get_port_configuration(out_port_uuid)
                  if ptype != port.ROUTER_PORT:
                    message = "Can't send ICMP. VirtualRouterNetwork's " \
                              "vrn_process_packet_match returned OUTPUT action with a " \
                              "non-materialized port."
                    logging.warn(message)
                    raise Exception, message

                // Now we have 2 cases:
                //  * The out_port is local - emit the icmp directly on the OpenFlow port
                //  * The out_port is remote - forward the icmp.
                out_port_num = self.map_port_uuid_to_num(out_port_uuid)
                if out_port_num is not None: // the port is local
                  ethsrc = self.get_hw_addr(out_port_uuid)
                  ethdst = pkt_data.src
                  ethpkt = ethernet.Ethernet(data=ippkt, type=ethernet.ETH_TYPE_IP,
                                             src=ethsrc, dst=ethdst)
                  self.send_unbuffered_packet_out_port((str(ethpkt),), out_port_num)
                elif ptype == port.ROUTER_PORT:
                  // The first uuid encoded in the ethernet addresses is that of the ingress
                  // port of the router from which the packet will be emitted. This is used
                  // to correctly set the source of any ICMPs the packet may trigger.
                  // In this case we're forwarding an ICMP (which isn't allowed to trigger
                  // ICMPs in turn) so we put the trigger port although it's incorrect.
                  (ethsrc, ethdst) = self._gen_tunnel_ethaddrs(trigger_port_uuid,
                                                               out_port_uuid,
                                                               ippkt.dst)
                  ethpkt = ethernet.Ethernet(data=ippkt, type=ethernet.ETH_TYPE_IP,
                                             src=ethsrc, dst=ethdst)
                  // Find the number of the tunnel port to this remote port.
                  peer_ip_address = self.map_port_uuid_to_ipv4_host_addr(out_port_uuid)
                  tun_port_num = self.get_tunnel_port_num(peer_ip_address)
                  if tun_port_num is None:
                    // The tunnel is down, we can't send the ICMP message. Do nothing.
                    logging.info('Could not send ICMP because tunnel is down')
                    pass
                  else:
                    self.send_unbuffered_packet_out_port((str(ethpkt),), tun_port_num)
                elif ptype == port.LOGICAL_ROUTER_PORT:
                  // If we got here and still have a logical router port that must mean
                  // there was a cycle in the VRN the FE chain was longer than 10.
                  logging.warn('Dropping ICMP error message: routing through VRN may have'
                               'encountered a cycle')
                else:
                  logging.warn('Dropping ICMP error message: unrecognized port type')
                  */
    }

    private void freeBuffer(PacketContext pktCtx) {
        // TODO Auto-generated method stub
        
    }

    private void notifyFlowAdded(MidoMatch origMatch, MidoMatch flowMatch,
            UUID inPortId, UUID lastInPortId, ForwardInfo fwdInfo,
            Set<UUID> routers) {
        // TODO Auto-generated method stub
        
    }

    private void installBlackhole(MidoMatch flowMatch, int bufferId,
            int hardTimeout) {
        // TODO Auto-generated method stub
        
    }

    private MidoMatch makeWildcarded(MidoMatch origMatch) {
        // TODO Auto-generated method stub
        return null;
    }

    private void processForwardedPacket(PacketContext pktCtx) {
        // TODO: Check for multicast packets we generated ourself for a group
        // we're in, and drop them.

        // TODO: Check for the broadcast address, and if so use the broadcast
        // ethernet address for the dst MAC.
        // We can check the broadcast address by looking up the gateway in
        // Zookeeper to get the prefix length of its network.

        // TODO: Do address spoofing prevention: if the source
        // address doesn't match the vport's, drop the flow.

        // Extract the gateway IP and vport uuid.
        byte[] dlSrc = pktCtx.origMatch.getDataLayerSource();
        byte[] dlDst = pktCtx.origMatch.getDataLayerDestination();
        pktCtx.lastInPortId = ingressUUIDFromDlHeaders(dlSrc, dlDst);
        pktCtx.lastEgPortId = egressUUIDFromDlHeaders(dlSrc, dlDst);
        pktCtx.gwNwAddr = gatewayNwAddrFromDlHeaders(dlSrc, dlDst);
        // If we don't own the egress port, there was a forwarding mistake.
        L3DevicePort devPort = devPortById.get(pktCtx.lastEgPortId);
        if (null == devPort) {
            // TODO: raise an exception or install a Blackhole?
            return;
        }
        // Set the of output port number.
        pktCtx.outPortNum = devPort.getNum();
        // Get the mac address. The PacketContext is the async callback.
        pktCtx.gwDlAddr = network.getMacForIp(pktCtx.lastEgPortId, pktCtx.gwNwAddr,
                    pktCtx);
        if (null != pktCtx.gwDlAddr)
            arpCompleted(pktCtx);
        // else, the arp will be completed asynchronously by the callback.
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        // TODO Auto-generated method stub
        // Get the Midolman UUID from OVSDB.
        UUID portId = null;
        // Now get the port configuration from ZooKeeper.
        L3DevicePort devPort = null;
        try {
            devPort = new L3DevicePort(portDir, portId);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        try {
            network.addPort(devPort);
        } catch (KeeperException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        devPortById.put(portId, devPort);
        devPortByNum.put(port.getPortNumber(), devPort);
    }

    @Override
    public void clear() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void sendFlowModDelete(boolean strict, OFMatch match, int priority,
            int outPort) {
        // TODO Auto-generated method stub
        
    }

}
