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

import org.opendaylight.ovsdb.lib.notation.Condition
import org.opendaylight.ovsdb.lib.notation.Function
import org.opendaylight.ovsdb.lib.operations.{Operation, Operations}
import org.opendaylight.ovsdb.lib.schema.{GenericTableSchema, DatabaseSchema}

/**
 * Created by ernest on 23/01/15.
 */
class OvsdbPhysicalSwitch(val dbs: DatabaseSchema) {
    import OvsdbPhysicalSwitch._

    private val physSwitchSchema =
        dbs.table(TB_PHYSICAL_SWITCH, classOf[GenericTableSchema])
    private val mgmtIpsColumn = physSwitchSchema.column("management_ips")
    private val tunnelIpsColumn = physSwitchSchema.column("tunnel_ips")

    def selectTunnelIps(vtep: VtepEndPoint)
        : Operation[GenericTableSchema] = {
        val matchedMgmntIp = new Condition(mgmtIpsColumn.getName,
                                           Function.EQUALS,
                                           vtep.mgmtIp.toString)
        val op = Operations.op.select(physSwitchSchema).column(tunnelIpsColumn)
        op.addCondition(matchedMgmntIp)
        op
    }
}

object OvsdbPhysicalSwitch {
    final val TB_PHYSICAL_SWITCH = "Physical_Switch"
}
