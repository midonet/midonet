package com.midokura.midolman.layer3;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.Router.Action;
import com.midokura.midolman.layer3.Router.ForwardInfo;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.state.NetworkDirectory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.util.Callback;

public class Network {

    private static final Logger log = LoggerFactory.getLogger(Network.class);
    private static final int MAX_HOPS = 10;

    protected UUID netId;
    private NetworkDirectory netDir;
    private RouterDirectory routerDir;
    private PortDirectory portDir;
    private Reactor reactor;
    private Map<UUID, Router> routers;
    private Map<UUID, Router> routersByPortId;
    private Set<Callback<UUID>> watchers;
    private Callback<UUID> myWatcher;

    public Network(UUID netId, NetworkDirectory netDir,
            RouterDirectory routerDir, PortDirectory portDir, Reactor reactor) {
        this.netId = netId;
        this.netDir = netDir;
        this.routerDir = routerDir;
        this.portDir = portDir;
        this.reactor = reactor;
        this.routers = new HashMap<UUID, Router>();
        this.routersByPortId = new HashMap<UUID, Router>();
        this.watchers = new HashSet<Callback<UUID>>();
        myWatcher = new Callback<UUID>() {
            public void call(UUID routerId) {
                notifyWatchers(routerId);
            }
        };
    }

    public void addWatcher(Callback<UUID> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Callback<UUID> watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers(UUID routerId) {
        for (Callback<UUID> watcher: watchers)
            // TODO(pino): should this be scheduled instead of directly called?
            watcher.call(routerId);
    }

    private Router getRouterByPort(UUID portId) {
        Router rtr = routersByPortId.get(portId);
        if (null != rtr)
            return rtr;
        return null;
        
        /*
    fe = self._port_uuid_to_fe.get(port_uuid)
    if fe is not None:
      return fe
    pconfig = self._port_dir.get_port_configuration(port_uuid)
    if pconfig is None:
      message = "Cannot create the forwarding element for port %s: no port " \
                "configuration found.", port_uuid
      logging.warn(message)
      raise Exception, message
    (port_type, port_config) = pconfig
    if port_type != port.ROUTER_PORT and port_type != port.LOGICAL_ROUTER_PORT:
      raise ValueError('port_uuid %s does not belong to a router port' %
                       str(port_uuid))
    fe = self._uuid_to_fe.get(port_config.router_uuid)
    if fe is None:
      fe = self._make_router_fe(port_config.router_uuid)
    self._port_uuid_to_fe[port_uuid] = fe
    return fe
         */
    }
    
    public void addPort(L3DevicePort port) throws KeeperException,
            InterruptedException, IOException, ClassNotFoundException {
        UUID routerId = port.getVirtualConfig().device_id;
        Router rtr = routers.get(routerId);
        if (null == rtr) {
            NatMapping natMap = null; // TODO(pino): finish this.
            RuleEngine ruleEngine = new RuleEngine(routerDir, routerId, natMap);
            ruleEngine.addWatcher(myWatcher);
            ReplicatedRoutingTable table = new ReplicatedRoutingTable(routerId,
                    routerDir.getRoutingTableDirectory(routerId));
            table.addWatcher(myWatcher);
            rtr = new Router(routerId, ruleEngine, table, portDir, reactor);
            routers.put(routerId, rtr);
        }
        rtr.addPort(port);
        routersByPortId.put(port.getId(), rtr);
    }

    // This should only be called for materialized ports, not logical ports.
    public void removePort(L3DevicePort port) throws KeeperException,
            InterruptedException {

    }

    public byte[] getMacForIp(UUID portId, int nwAddr, Callback<byte[]> cb) {
        return null;
    }

    public Action process(MidoMatch pktMatch, byte[] packet, UUID inPortId,
            ForwardInfo fwdInfo, Collection<UUID> traversedRouters) {
        Router rtr = routersByPortId.get(inPortId);
        if (null == rtr)
            throw new RuntimeException("Packet arrived on a port that hasn't " +
                    "been added to the network yet.");

        for (int i=0; i< MAX_HOPS; i++) {
            traversedRouters.add(rtr.routerId);
            Action action = rtr.process(pktMatch, packet, inPortId, fwdInfo);
            if (action.equals(Action.FORWARD)) {
                // Get the port's configuration to see if it's logical.
            }
        }
        return null;

        /*
         * get_port_configuration(fwd_action.args.out_port_uuid) if
         * out_port_type == port.LOGICAL_ROUTER_PORT: # The packet needs to be
         * processed by the router that owns the # out_port's peer. peer_uuid =
         * out_port_config.peer_uuid # TODO(pino): sanity check that peer_uuid
         * is not None? fe = self._get_fe_for_port_uuid(peer_uuid) if
         * fe._router_uuid in fe_traversed: message = 'Detected a routing loop'
         * logging.warn(message) raise Exception, message match =
         * fwd_action.args.new_match in_port_uuid=peer_uuid continue # If we got
         * here, return fwd_action to the caller. One of these holds: # 1) the
         * action is OUTPUT and the port type is not logical OR # 2) the action
         * is not OUTPUT
         * logging.debug('VirtualRouterNetwork::vrn_process_packet_match
         * returning ' 'action %s' % (fwd_action,))
         * 
         * return (fwd_action, in_port_uuid, fe_traversed) # If we got here it
         * means that we already traversed _MAX_PATH_LENGTH # router FE's
         * without reaching a materialized router port. msg = 'Traversed %s
         * routers without finding a materialized port' % i raise Exception, msg
         */
    }
}
