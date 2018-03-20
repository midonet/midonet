package org.midonet.midolman.topology

import java.util.UUID

import rx.Observable

import org.midonet.cluster.models.Neutron.{HostPortBinding => TopologyHostPortBinding}
import org.midonet.midolman.simulation.{HostPortBinding => SimulationHostPortBinding}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}
import org.midonet.cluster.util.UUIDUtil.fromProto

class HostPortBindingMapper(id: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper(classOf[SimulationHostPortBinding], id, vt) {

    lazy val observable: Observable[SimulationHostPortBinding] =
        try {
            vt.store.observable(classOf[TopologyHostPortBinding], id)
                .observeOn(vt.vtScheduler)
                .map[SimulationHostPortBinding](makeFunc1(buildHostPortBinding))
                .distinctUntilChanged()
        } finally {
            Observable.empty()
        }

    private def buildHostPortBinding(input: TopologyHostPortBinding) = {
        new SimulationHostPortBinding(input.getId, input.getInterfaceName)
    }
}
