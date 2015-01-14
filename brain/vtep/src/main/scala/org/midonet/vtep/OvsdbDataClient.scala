/*
 * Copyright 2014 Midokura SARL
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

import java.net.InetAddress
import java.util
import java.util.UUID
import java.util.{ArrayList, List => JavaList}
import javax.annotation.concurrent.GuardedBy

import org.midonet.packets.IPv4Addr
import org.midonet.vtep.model.LogicalSwitch
import org.opendaylight.ovsdb.lib.notation.{Column, Condition, Function}
import org.opendaylight.ovsdb.lib.operations.{Operations, TransactionBuilder, Operation, Select}
import org.opendaylight.ovsdb.lib.schema.typed.TypedBaseTable
import org.opendaylight.ovsdb.lib.schema.{ColumnSchema, GenericTableSchema, TableSchema, DatabaseSchema}
import org.opendaylight.ovsdb.schema.hardwarevtep.PhysicalSwitch
import rx.{Observer, Observable}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import com.google.common.util.concurrent.{FutureCallback, Futures, Monitor}
import com.google.common.util.concurrent.Monitor.Guard
import org.opendaylight.ovsdb.lib.impl.OvsdbConnectionService
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.slf4j.LoggerFactory

import scala.concurrent.{Promise, Future}
import scala.util.Try

/**
 * The class representing an ovsdb-based VTEP
 */
class OvsdbDataClient(val endPoint: VtepEndPoint)
    extends VtepConnection with VtepDataClient {

    import VtepConnection.State._

    private val log = LoggerFactory.getLogger(classOf[OvsdbDataClient])
    private val monitor = new Monitor()
    private val isOperative = new Guard(monitor) {
        override def isSatisfied: Boolean = state != DISPOSED
    }

    @GuardedBy("monitor")
    private var state = if (endPoint.mgmtIp == null) DISPOSED else DISCONNECTED
    @GuardedBy("monitor")
    private var client: OvsdbClient = null
    @GuardedBy("monitor")
    private val users = new mutable.HashSet[UUID]()

    private val connectionService = OvsdbConnectionService.getService

    override def getManagementIp = endPoint.mgmtIp
    override def getManagementPort = endPoint.mgmtPort

    override def connect(user: UUID) = {
        if (!monitor.enterIf(isOperative)) {
            throw new VtepStateException(endPoint, "cannot be connected")
        }
        try {
            log.info("Connecting to VTEP on {} for user " + user, endPoint)
            val address = try {
                InetAddress.getByName(endPoint.mgmtIp.toString)
            } catch {
                case e: Throwable =>
                    state = DISPOSED
                    throw new VtepStateException(endPoint, "invalid IP address")
            }

            // Skip if already connected
            if (state == DISCONNECTED) {
                client = connectionService.connect(address, endPoint.mgmtPort)
                state = CONNECTED
            }
            users.add(user)
        } catch {
            case e: VtepException =>
                // state has already been processed
                throw e
            case e: Throwable =>
                state = BROKEN
                throw new VtepStateException(endPoint, "connection failed", e)
        } finally {
            monitor.leave()
        }
    }

    override def disconnect(user: UUID) = {
        if (!monitor.enterIf(isOperative)) {
            throw new VtepStateException(endPoint, "cannot be disconnected")
        }
        try {
            log.info("Disconnecting from VTEP on {} for user " + user, endPoint)

            // Skip if not connected
            if (state == CONNECTED) {
                if (users.remove(user) && users.isEmpty) {
                    connectionService.disconnect(client)
                    client = null
                    state = DISCONNECTED
                }
            }
        } finally {
            monitor.leave()
        }
    }

    @GuardedBy("monitor")
    override def getState: VtepConnection.State.Value = state

    override def vxlanTunnelIp: Option[IPv4Addr] = ???

    override def macLocalUpdates: Observable[MacLocation] = ???

    override def macRemoteUpdates: Observer[MacLocation] = ???

    override def currentMacLocal: Seq[MacLocation] = ???

    override def ensureLogicalSwitch(name: String, vni: Int): Try[LogicalSwitch] = ???

    override def removeLogicalSwitch(name: String): Try[Unit] = ???

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)]): Try[Unit] = ???
}

/*
class OvsdbData(val client: OvsdbClient) {
    import OvsdbData._

    private val connInfo = client.getConnectionInfo
    val endPoint = VtepEndPoint(
        IPv4Addr.fromBytes(connInfo.getRemoteAddress.getAddress),
        connInfo.getRemotePort)

    private val tableCache = Map[String, Promise[_ <: TypedBaseTable]](
        TB_PHYSICAL_SWITCH -> Promise[PhysicalSwitch]()
    )
    private val physicalSwitchTable: Promise[PhysicalSwitch] =
        tableCache(TB_PHYSICAL_SWITCH).asInstanceOf[Promise[PhysicalSwitch]]

    private val dbReady: Future[DatabaseSchema] = initialize()
    private def initialize(): Future[DatabaseSchema] = {
        type DatabaseList = JavaList[String]
        // look for vtep database
        val db: Promise[DatabaseSchema] = Promise()
        Futures.addCallback(client.getDatabases,
            new FutureCallback[DatabaseList] {
                override def onFailure(err: Throwable): Unit =
                    db.failure(err)
                override def onSuccess(dbl: DatabaseList): Unit =
                    if (!dbl.contains(DB_HARDWARE_VTEP))
                        db.failure(new VtepUnsupportedException(
                            endPoint, "unsupported ovsdb end point"))
                    else
                        Futures.addCallback(
                            client.getSchema(DB_HARDWARE_VTEP),
                            new FutureCallback[DatabaseSchema] {
                                override def onFailure(exc: Throwable): Unit =
                                    db.failure(exc)
                                override def onSuccess(dbs: DatabaseSchema): Unit =
                                    db.success(dbs)
                            }
                        )
            }
        )
        db.future.onFailure(
            {case err => tableCache.values.foreach({_.failure(err)})})
        db.future.onSuccess({case _ =>
            val pst = client.getTypedRowWrapper(classOf[PhysicalSwitch], null)
            if (pst == null)
                physicalSwitchTable.failure(new VtepUnsupportedException(
                    endPoint, "unsupported ovsdb physical switch table"))
            else
                physicalSwitchTable.success(pst)
        })
        db.future
    }


    def getTunnelIps[E](): Future[JavaList[IPv4Addr]] = {
        val tunnelIps: Promise[JavaList[IPv4Addr]] = Promise()
        dbReady.onSuccess({case dbs =>
                val table = dbs.table(TB_PHYSICAL_SWITCH, classOf[GenericTableSchema])
                val tunnelIps = ColumnSchema[GenericTableSchema, util.Set[String]] = table.multiValuedColumn("tunnel_ips", classOf[String])
                val tBuilder: TransactionBuilder = client.transactBuilder(dbs)
                    .add(Operations.op.select(table))

        })
        val ovsTable:
        val f = for {d <- dbReady; s <- physicalSwitchTable.future} yield (d, s)
        f.onSuccess(
        {case (dbs, ps) =>
                val ops: JavaList[Operation[_ <: TableSchema[E]]] =
                    new util.ArrayList()
                client.transact(dbs, ops)
        })


        tableCache(TB_PHYSICAL_SWITCH).future.onFailure(
            {case err => tunnelIps.failure(err)})
        tableCache(TB_PHYSICAL_SWITCH).asInstanceOf[Promise[PhysicalSwitch]]
            .future.onSuccess(
            {
                case _ =>
            })
        tunnelIps.future
    }

}

object OvsdbData {
    private val DB_HARDWARE_VTEP = "hardware_vtep"
    private val TB_PHYSICAL_SWITCH = "Physical_Switch"
}

object OvsdbDataClient {
    private val DB_HARDWARE_VTEP = "hardware_vtep"
    private val TB_PHYSICAL_SWITCH = "Physical_Switch"

    def getDatabase(cl: OvsdbClient): Future[DatabaseSchema] = {
        val db: Promise[DatabaseSchema] = Promise()
        Futures.addCallback(cl.getSchema(DB_HARDWARE_VTEP),
            new FutureCallback[DatabaseSchema] {
                override def onFailure(err: Throwable): Unit =
                    db.failure(err)
                override def onSuccess(dbs: DatabaseSchema): Unit =
                    db.success(dbs)
        })
        db.future
    }

    def getPhysicalSwitchTable(cl: OvsdbClient): PhysicalSwitch = {
        cl.getTypedRowWrapper(classOf[PhysicalSwitch], null)
    }

    private def colMatch[SCHEMA, T](col: Column[SCHEMA, T], value: Any) =
        new Condition(col.getSchema.getName, Function.EQUALS, value)

    def getTunnelIps(cl: OvsdbClient, dbs: DatabaseSchema, mgmtIp: IPv4Addr) = {
        val ps = getPhysicalSwitchTable(cl)
        val matchIp = colMatch(ps.getManagementIpsColumn, mgmtIp.toString)
        val select = new Select(ps.getSchema)
        select.column(ps.getTunnelIpsColumn.getSchema)
        select.where(matchIp)
        val ops: ArrayList[select.getClass] =

        cl.transact(dbs,)
    }
}

*/

