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

import java.util

import com.google.common.util.concurrent.{FutureCallback, Futures}
import org.opendaylight.ovsdb.lib.OvsdbClient

import org.midonet.packets.IPv4Addr
import org.opendaylight.ovsdb.lib.notation.Row
import org.opendaylight.ovsdb.lib.operations.OperationResult
import org.opendaylight.ovsdb.lib.schema.{GenericTableSchema, DatabaseSchema}

import scala.concurrent.{Promise, Future}
import scala.util.{Success, Failure}

/**
 * Created by ernest on 23/01/15.
 */
class OvsdbVtepData(val client: OvsdbClient) {
    import OvsdbVtepData._

    private val connInfo = client.getConnectionInfo
    val endPoint = VtepEndPoint(
        IPv4Addr.fromBytes(connInfo.getRemoteAddress.getAddress),
        connInfo.getRemotePort)

    private val dbReady: Promise[DatabaseSchema] = Promise()
    private val physSwitch: Promise[OvsdbPhysicalSwitch] = Promise()
    initialize()

    private def initialize(): Unit = {
        type DatabaseList = util.List[String]
        // look for vtep database schemas
        Futures.addCallback(client.getDatabases,
                            new FutureCallback[DatabaseList] {
            override def onFailure(err: Throwable): Unit =
                dbReady.failure(err)
            override def onSuccess(dbl: DatabaseList): Unit =
                if (!dbl.contains(DB_HARDWARE_VTEP))
                    dbReady.failure(new VtepUnsupportedException(endPoint,
                        "unsupported ovsdb end point"))
                else
                    Futures.addCallback(
                        client.getSchema(DB_HARDWARE_VTEP),
                        new FutureCallback[DatabaseSchema] {
                            override def onFailure(exc: Throwable): Unit =
                                dbReady.failure(exc)
                            override def onSuccess(dbs: DatabaseSchema): Unit = {
                                dbReady.success(dbs)
                                try {
                                    physSwitch.success(
                                        new OvsdbPhysicalSwitch(dbs))
                                } catch {
                                    case e: Throwable => physSwitch.failure(e)
                                }
                            }
                        }
                    )
        })
    }

    private def getTunnelIps: Future[util.List[IPv4Addr]] = {
        val result = Promise[util.List[IPv4Addr]]()
        val ready = for {db <- dbReady.future; ps <- physSwitch.future}
                    yield (db, ps)
        ready.onComplete({
            case Failure(exc) => throw exc
            case Success((dbs, phSwitch)) =>
                val transaction = client.transactBuilder(dbs)
                //transaction.add(phSwitch.selectTunnelIps(endPoint))
                Futures.addCallback(transaction.execute(),
                    new FutureCallback[util.List[OperationResult]] {
                    override def onFailure(t: Throwable): Unit =
                        result.failure(t)
                    override def onSuccess(r: util.List[OperationResult]): Unit = {
                        val ls = r.get(0)
                        val ipList: util.List[IPv4Addr] = new util.ArrayList()
                        for (row: Row[GenericTableSchema] <- ls.getRows) {
                            row.
                        }
                    }
                })

        })
        result.future
    }

}

object OvsdbVtepData {
    final val DB_HARDWARE_VTEP = "hardware_vtep"
}
