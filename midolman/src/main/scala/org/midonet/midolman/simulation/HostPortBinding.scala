package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.sdn.flows.FlowTagger


class HostPortBinding(val id: UUID, val interfaceName: String) extends VirtualDevice {
    val deviceTag = FlowTagger.tagForPort(id)
}

