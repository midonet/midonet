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
package org.midonet.midolman.util.mock

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import org.midonet.midolman.PacketWorker
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io._
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp._
import org.midonet.util._

class MockUpcallDatapathConnectionManager(config: MidolmanConfig)
        extends UpcallDatapathConnectionManagerBase(config,
            new TokenBucketPolicy(config, new TokenBucketTestRate, 1,
                                  _ => Bucket.BOTTOMLESS)) {

    val conn = new MockManagedDatapathConnection()

    var upcallHandler: BatchCollector[Packet] = null

    object NullPacketWorker extends PacketWorker {
        override def submit(packet: Packet) = true
    }

    def initialize()(implicit ec: ExecutionContext, as: ActorSystem) {
        if (upcallHandler == null) {
            upcallHandler = makeUpcallHandler(IndexedSeq(NullPacketWorker))
            conn.getConnection.datapathsSetNotificationHandler(upcallHandler)
        }
    }

    override def makeConnection(name: String, bucket: Bucket,
                                t: ChannelType) = null

    override def stopConnection(conn: ManagedDatapathConnection) {}

    override protected def setUpcallHandler(conn: OvsDatapathConnection) {
        conn.datapathsSetNotificationHandler(upcallHandler)
    }

    override def createAndHookDpPort(dp: Datapath, port: DpPort, t: ChannelType)(
            implicit ec: ExecutionContext, as: ActorSystem)
    : Future[(DpPort,Int)] = {
        initialize()
        val ovsConOps = new OvsConnectionOps(conn.getConnection)
        ovsConOps createPort (port, dp) map { (_, 1) }
    }

    override def deleteDpPort(dp: Datapath, port: DpPort)(
            implicit ec: ExecutionContext, as: ActorSystem): Future[Boolean] = {
        val ovsConOps = new OvsConnectionOps(conn.getConnection)
        ovsConOps delPort(port, dp) map { _ => true }
    }

}
