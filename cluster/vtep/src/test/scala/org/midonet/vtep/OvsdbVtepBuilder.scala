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

package org.midonet.vtep

import java.util.UUID

import scala.concurrent.duration._
import scala.util.Random

import org.opendaylight.ovsdb.lib.schema.DatabaseSchema

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.{MAC, IPv4Addr}
import org.midonet.vtep.mock.InMemoryOvsdbVtep
import org.midonet.vtep.schema.{UcastMacsLocalTable, LogicalSwitchTable, PhysicalPortTable, PhysicalSwitchTable}

object OvsdbVtepBuilder {

    private val random = new Random()

    implicit def asVtepBuilder(vtep: InMemoryOvsdbVtep): OvsdbVtepBuilder = {
        new OvsdbVtepBuilder(vtep)
    }

}

class OvsdbVtepBuilder(val vtep: InMemoryOvsdbVtep) extends AnyVal {

    private def schema: DatabaseSchema = {
        OvsdbTools.getDbSchema(vtep.getHandle, OvsdbTools.DB_HARDWARE_VTEP)
            .result(5 seconds)
    }

    def endPoint: VtepEndPoint = {
        OvsdbTools.endPointFromOvsdbClient(vtep.getHandle)
    }

    def createPhysicalPort(id: UUID = UUID.randomUUID(),
                           portName: String = "",
                           portDescription: String = ""): PhysicalPort = {
        PhysicalPort(id, portName, portDescription)
    }

    def createPhysicalSwitch(id: UUID = UUID.randomUUID(),
                             vxlanIp: IPv4Addr = IPv4Addr.random,
                             vtepName: String = "",
                             vtepDescription: String = "",
                             ports: Seq[PhysicalPort] = Seq.empty)
    : PhysicalSwitch = {
        val psTable = new PhysicalSwitchTable(schema)
        val portTable = new PhysicalPortTable(schema)
        val ps = PhysicalSwitch(id, vtepName, vtepDescription,
                                ports.map(_.uuid).toSet, Set(endPoint.mgmtIp),
                                Set(vxlanIp))
        vtep.putEntry(psTable, ps, ps.getClass)
        ports.foreach(p => vtep.putEntry(portTable, p, p.getClass))
        ps
    }

    def createLogicalSwitch(id: UUID = UUID.randomUUID(),
                            tunnelKey: Int = OvsdbVtepBuilder.random.nextInt(4096),
                            lsDescription: String = ""): LogicalSwitch = {
        val lsName = LogicalSwitch.networkIdToLogicalSwitchName(UUID.randomUUID())
        val lsTable = new LogicalSwitchTable(schema)
        val ls = new LogicalSwitch(id, lsName, tunnelKey, lsDescription)
        vtep.putEntry(lsTable, ls, ls.getClass)
        ls
    }

    def createLocalUcastMac(ls: UUID,
                            mac: String = MAC.random().toString,
                            ip: IPv4Addr = IPv4Addr.random,
                            locator: UUID = UUID.randomUUID): UcastMac = {
        val uLocalTable = new UcastMacsLocalTable(schema)
        val uMac = UcastMac(UUID.randomUUID, ls, mac, ip, locator)
        vtep.putEntry(uLocalTable, uMac, uMac.getClass)
        uMac
    }
}
