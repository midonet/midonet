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

package org.midonet.midolman

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.parallel.mutable

import org.midonet.packets.IPv4Addr

// TODO: make this class thread-safe
class VxlanRecircMap(val base: Int = 11000) {
    private val routerToInt = mutable.ParHashMap[UUID, Int]()
    private val intToRouter = mutable.ParHashMap[Int, UUID]()
    private val routerIndex = new AtomicInteger(base)

    private val portVtepToInt = mutable.ParHashMap[(UUID, IPv4Addr), Int]()
    private val intToPortVtep = mutable.ParHashMap[Int, (UUID, IPv4Addr)]()
    private val portVtepIndex = new AtomicInteger(base)

    def intToRouter(i: Int): Option[UUID] = intToRouter.get(i)

    def routerToInt(routerId: UUID): Int = {
        routerToInt.get(routerId) match {
            case Some(i) =>
                i
            case None =>
                val i = routerIndex.getAndIncrement
                routerToInt.put(routerId, i)
                intToRouter.put(i, routerId)
                i
        }
    }

    def intToPortVtep(i: Int): Option[(UUID, IPv4Addr)] = intToPortVtep.get(i)

    def portVtepToInt(portId: UUID, vtep: IPv4Addr): Int = {
        val portVtep = (portId, vtep)
        portVtepToInt.get(portVtep) match {
            case Some(i) =>
                i
            case None =>
                val i = portVtepIndex.getAndIncrement
                portVtepToInt.put(portVtep, i)
                intToPortVtep.put(i, portVtep)
                i
        }
    }

    def intToBytePair(i: Int): (Byte, Byte) = ((i >>> 8).toByte, i.toByte)
    def bytePairToInt(b1: Byte, b2: Byte): Int = (b1 << 8) | b2
}
