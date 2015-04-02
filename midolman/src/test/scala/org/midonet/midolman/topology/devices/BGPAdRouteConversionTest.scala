package org.midonet.midolman.topology.devices

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.midolman.topology.routing.BGPAdRoute
import org.midonet.midolman.topology.{TopologyMatchers, TopologyBuilder}
import org.midonet.midolman.topology.TopologyBuilder.randomIPv4Subnet

@RunWith(classOf[JUnitRunner])
class BGPAdRouteConversionTest extends FlatSpec with Matchers
                             with TopologyBuilder with TopologyMatchers {

    "BGP route" should "convert from Protocol Buffers" in {
        val proto = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                   bgpId = Some(UUID.randomUUID))
        val route = ZoomConvert.fromProto(proto, classOf[BGPAdRoute])

        route shouldBeDeviceOf proto
    }

    "BGP route" should "convert to Protocol Buffers" in {
        val route = new BGPAdRoute(UUID.randomUUID, randomIPv4Subnet,
                                 UUID.randomUUID)
        val proto = ZoomConvert.toProto(route, classOf[Topology.BgpAdRoute])

        route shouldBeDeviceOf proto
    }
}
