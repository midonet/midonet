package org.midonet.midolman.topology.devices

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.midolman.topology.routing.BgpRoute
import org.midonet.midolman.topology.{TopologyMatchers, TopologyBuilder}
import org.midonet.midolman.topology.TopologyBuilder.randomIPv4Subnet

@RunWith(classOf[JUnitRunner])
class BgpRouteConversionTest extends FlatSpec with Matchers
                             with TopologyBuilder with TopologyMatchers {

    "BGP route" should "convert from Protocol Buffers" in {
        val proto = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                   bgpId = Some(UUID.randomUUID))
        val route = ZoomConvert.fromProto(proto, classOf[BgpRoute])

        route shouldBeDeviceOf proto
    }

}
