/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.state

import java.nio.{BufferUnderflowException, ByteBuffer}
import java.util.UUID

import org.apache.commons.codec.DecoderException
import org.apache.commons.codec.binary.Hex

import rx.Observable

import org.midonet.cluster.data.storage.{MultiValueKey, StateResult, StateStorage}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend.RoutesKey
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.util.functors._

object RoutingTableStorage {

    private final val NoRoutes = Set.empty[Route]

    implicit def asRoutingTable(store: StateStorage): RoutingTableStorage = {
        new RoutingTableStorage(store)
    }

    @inline private def serializeForPort(route: Route): String = {
        val buffer = ByteBuffer.allocate(34)
        buffer.putInt(route.dstNetworkAddr)
        buffer.put(route.dstNetworkLength.toByte)
        buffer.putInt(route.srcNetworkAddr)
        buffer.put(route.srcNetworkLength.toByte)
        buffer.putInt(route.nextHopGateway)
        buffer.putInt(route.weight)
        buffer.putLong(route.routerId.getMostSignificantBits)
        buffer.putLong(route.routerId.getLeastSignificantBits)
        Hex.encodeHexString(buffer.array())
    }

    @inline private def deserializeForPort(portId: UUID, string: String)
    : Option[Route] = {
        try {
            val buffer = ByteBuffer.wrap(Hex.decodeHex(string.toCharArray))
            val dstNetworkAddr = buffer.getInt
            val dstNetworkLength = buffer.get
            val srcNetworkAddr = buffer.getInt
            val srcNetworkLength = buffer.get
            val nextHopGateway = buffer.getInt
            val weight = buffer.getInt
            val routerIdMsb = buffer.getLong
            val routerIdLsb = buffer.getLong
            Some(new Route(srcNetworkAddr, srcNetworkLength, dstNetworkAddr,
                           dstNetworkLength, NextHop.PORT, portId,
                           nextHopGateway, weight, "",
                           new UUID(routerIdMsb, routerIdLsb), true))
        } catch {
            case e @ (_: DecoderException | _: BufferUnderflowException) => None
        }
    }
}

/**
 * A wrapper class around the [[StateStorage]] with utility methods for adding,
 * removing and observing routes. Currently, only [[NextHop.PORT]] routes are
 * supported and they managed as state values for existing ports using a
 * [[RoutesKey]] state key, of multiple type (i.e. the key may store one or more
 * ephemeral values).
 *
 * Since the ZooKeeper implementation stores a value for multi-value keys as the
 * name of a z-node, routes use a 68 character hex-string serialization to
 * reduce their memory footprint, as follows:
 *
 *  8 chars - destination IPv4 address (32 bits)
 *  2 chars - destination prefix length (8 bits)
 *  8 chars - source IPv4 address (32 bits)
 *  2 chars - source prefix length (8 bits)
 *  8 chars - next hop IPv4 address (32 bits)
 *  8 chars - metric (32 bits)
 *  16 chars - router identifier (128 bits)
 *
 * Note that the ZooKeeper implementation of the multi-value keys uses
 * `getChildren` to read key updates. For large routing tables, this requires a
 * ZK client buffer greater than the default value. To this end, adjust the
 * buffer size using the `jute.maxbuffer` system property before the
 * initialization of the backend storage. As a rule of thumb, the buffer size
 * should be greater than 72 times the number of routes expected in the routing
 * table, i.e. greater than 36MB for 500,000 routes.
 *
 * The following benchmarking results illustrate the read/write routing table
 * performance for a single host with a ZooKeeper backend. They are intended for
 * comparative purposes only.
 *
 * CPU: 2.4 GHz Intel Core i5
 * Memory: 16 GB 1600 MHz DDR3
 * Storage: SSD
 *
 * Synchronous addition:
 *
 *  10K routes :   5381.594 ±  2571.315 ms
 *  50K routes :  26507.229 ±  2514.686 ms
 * 100K routes :  55203.471 ± 13388.467 ms
 * 250K routes : 132200.461 ± 34248.147 ms
 * 500K routes : 254076.862 ± 53597.53  ms
 *
 * Asynchronous addition:
 *
 *  10K routes :   1342.755 ±  1193.224 ms
 *  50K routes :   4732.607 ±  2097.779 ms
 * 100K routes :  10576.712 ±  4125.273 ms
 * 250K routes :  27739.252 ±  6165.878 ms
 * 500K routes :  52202.811 ± 10616.363 ms
 *
 * Synchronous addition followed by removal:
 *
 *  10K routes :   6819.777 ±   344.639 ms
 *  50K routes :  34126.009 ±  1235.492 ms
 * 100K routes :  75578.185 ±  1160.292 ms
 * 250K routes : 177636.893 ± 12260.099 ms
 * 500K routes : 340418.424 ± 17747.760 ms
 *
 * Asynchronous addition followed by removal:
 *
 *  10K routes :   2328.891 ±  414.151 ms
 *  50K routes :   9924.398 ±  667.873 ms
 * 100K routes :  16563.857 ±  607.327 ms
 * 250K routes :  47584.362 ± 8720.371 ms
 * 500K routes :  91988.800 ± 7349.473 ms
 *
 * TODO: Synchronous addition with parallel read via observable
 *
 *  10K routes :  ~120 s
 *
 * TODO: Asynchronous addition with parallel read via observable
 *
 */
class RoutingTableStorage(val store: StateStorage) extends AnyVal {

    /** Adds a [[NextHop.PORT]] route as a state value to the next hop port
      * object. Adding a route with a different [[NextHop]] is not supported. */
    def addRoute(route: Route): Observable[StateResult] = {
        route.nextHop match {
            case NextHop.PORT =>
                store.addValue(classOf[Port], route.nextHopPort, RoutesKey,
                               serializeForPort(route))
            case _ =>
                throw new IllegalArgumentException(
                    s"Route next hop ${route.nextHop} not supported")
        }
    }

    /** Removes a [[NextHop.PORT]] route from the state key of the corresponding
      * next hop port. */
    def removeRoute(route: Route): Observable[StateResult] = {
        route.nextHop match {
            case NextHop.PORT =>
                store.removeValue(classOf[Port], route.nextHopPort, RoutesKey,
                                  serializeForPort(route))
            case _ =>
                throw new IllegalArgumentException(
                    s"Route next hop ${route.nextHop} not supported")
        }
    }

    /** Fetches the set of routes from the state key of the given port. */
    def getPortRoutes(portId: UUID): Observable[Set[Route]] = {
        store.getKey(classOf[Port], portId, RoutesKey) map makeFunc1 {
            case MultiValueKey(_, values) =>
                values.flatMap(deserializeForPort(portId, _))
            case _ => NoRoutes
        }
    }

    /** Provides an observable for the set of routes for a given port. */
    def portRoutesObservable(portId: UUID): Observable[Set[Route]] = {
        store.keyObservable(classOf[Port], portId, RoutesKey) map makeFunc1 {
            case MultiValueKey(_, values) =>
                values.flatMap(deserializeForPort(portId, _))
            case _ => NoRoutes
        }
    }

}
