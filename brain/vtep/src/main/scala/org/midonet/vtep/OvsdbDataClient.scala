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

import java.util.concurrent.Executors

import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

import org.slf4j.LoggerFactory
import rx.{Observer, Observable}

import org.midonet.packets.IPv4Addr
import org.midonet.vtep.model.LogicalSwitch

/**
 * The class representing an ovsdb-based VTEP
 */
class OvsdbDataClient(val connection: OvsdbVtepConnection)
    extends VtepDataClient {

    // TODO: exec ctx should be passed as a parameter, to unify with connectionClient
    private implicit val executor = Executors.newSingleThreadExecutor()
    private implicit val executionContext = ExecutionContext.fromExecutor(executor)

    private val log = LoggerFactory.getLogger(classOf[OvsdbDataClient])

    private val vtepData = new OvsdbVtepData(connection.ovsdb, executionContext)

    override def vxlanTunnelIp: Option[IPv4Addr] = {
        Await.ready(vtepData.getTunnelIp.future, Duration.Inf).value match {
            case Some(Success(ip)) => Some(ip)
            case Some(Failure(none: NoSuchElementException)) => None
            case Some(Failure(exception)) => throw exception
            case None => None
        }
    }

    override def macLocalUpdates: Observable[MacLocation] = ???

    override def macRemoteUpdates: Observer[MacLocation] = ???

    override def currentMacLocal: Seq[MacLocation] = ???

    override def ensureLogicalSwitch(name: String, vni: Int): Try[LogicalSwitch] = ???

    override def removeLogicalSwitch(name: String): Try[Unit] = ???

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)]): Try[Unit] = ???
}


