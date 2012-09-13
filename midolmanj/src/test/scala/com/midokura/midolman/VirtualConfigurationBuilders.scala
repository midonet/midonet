/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman

import java.util.UUID
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Port, Ports}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.DataClient
import com.midokura.midonet.cluster.data.zones.GreTunnelZone
import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort

trait VirtualConfigurationBuilders {

    protected def clusterDataClient(): DataClient

    def newHost(name: String, id: UUID): Host = {
        val host = new Host().setName(name)
        clusterDataClient().hostsCreate(id, host)
        host.setId(id)
        host
    }

    def newHost(name: String): Host = newHost(name, UUID.randomUUID())

    def greTunnelZone(name: String): GreTunnelZone = {
        val tunnelZone = new GreTunnelZone().setName("default")
        clusterDataClient().tunnelZonesCreate(tunnelZone)
        tunnelZone
    }

    def newBridge(bridge: ClusterBridge): ClusterBridge = {
        clusterDataClient().bridgesGet(
            clusterDataClient().bridgesCreate(
                bridge
            )
        )
    }

    def newBridge(name: String): ClusterBridge = newBridge(new ClusterBridge().setName("bridge"))

    def newPortOnBridge(bridge: ClusterBridge, port: MaterializedBridgePort) =
        clusterDataClient().portsGet(clusterDataClient().portsCreate(port)).asInstanceOf[MaterializedBridgePort]


    def newPortOnBridge(bridge: ClusterBridge):MaterializedBridgePort =
        newPortOnBridge(bridge, Ports.materializedBridgePort(bridge))


    def materializePort(port: Port[_, _], host: Host, name: String) {
        clusterDataClient().hostsAddVrnPortMapping(host.getId, port.getId, name)
    }
}
