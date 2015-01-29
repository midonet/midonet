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

package org.midonet.brain.services.topology

import java.util.concurrent.TimeoutException

import com.google.inject.Inject
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rpc.Commands.Response.{Update, Deletion}
import org.midonet.cluster.rpc.Commands.{ID, Response}
import org.midonet.cluster.services.topology.client.ClientSession
import org.midonet.cluster.util.UUIDUtil

import org.slf4j.LoggerFactory

import org.midonet.brain.{ClusterNode, MinionConfig, ClusterMinion}
import org.midonet.config._
import rx.{Observer, Subscription}

import scala.concurrent.duration._

/**
 * A topology API client that establishes a client session to the
 * Topology Service and subscribes to all available topology objects,
 * dumping the updates and deletions to a log.
 */
class TopologySnoopy @Inject()(val nodeContext: ClusterNode.Context,
                               val cfg: TopologySnoopyConfig)
    extends ClusterMinion(nodeContext) {
    private val log = LoggerFactory.getLogger(classOf[TopologySnoopy])

    private val session = new ClientSession(cfg.getHost, cfg.getPort,
                                            cfg.getWsPath)
    private var subscription: Subscription = null

    private val types: Array[Topology.Type] = Topology.Type.values()

    @Override
    def doStart(): Unit = {
        log.info("Starting Midonet Topology Snoopy")
        try {
            types foreach session.watchAll
            subscription = session.connect().subscribe(new Observer[Response] {
                override def onCompleted(): Unit = {
                    log.info("No more updates available")
                }
                override def onError(e: Throwable): Unit = {
                    log.warn("Update flow interrupted", e)
                }
                override def onNext(msg: Response): Unit = {
                    log.info(prettyString(msg))
                }
                log.info("Client started")
            })
            notifyStarted()
        } catch {
            case e: Throwable =>
                log.warn("Client start failed")
                notifyFailed(e)
        }
    }

    @Override
    def doStop(): Unit = {
        log.info("Stopping Midonet Topology Snoopy")
        types foreach {t => session.unwatchAll(t)}
        session.terminate()
        try {
            session.awaitTermination(10.second)
            log.info("Client stopped")
            notifyStopped()
        } catch {
            case e: TimeoutException => try {
                session.terminateNow()
                session.awaitTermination(10.second)
            } catch {
                case e: Throwable =>
                    log.warn("Client termination failed", e)
                    notifyFailed(e)
            }
            case e: Throwable =>
                log.warn("Client termination failed", e)
                notifyFailed(e)
        }
    }

    def awaitTermination(atMost: Duration): Option[Throwable] =
        session.awaitTermination(atMost)

    private def prettyString(msg: Response): String = msg match {
        case r: Response if r.hasUpdate => prettyString(r.getUpdate)
        case r: Response if r.hasDeletion => prettyString(r.getDeletion)
        case other => "UNIDENTIFIED MESSAGE: " + other.toString
    }

    private def prettyString(update: Update): String =
        "UPDATE: " + (update match {
            case u: Update if u.hasChain =>
                "CHAIN " + prettyString(u.getChain.getId)
            case u: Update if u.hasHost =>
                "HOST " + prettyString(u.getHost.getId)
            case u: Update if u.hasIpAddrGroup =>
                "IPADDRGROUP " + prettyString(u.getIpAddrGroup.getId)
            case u: Update if u.hasNetwork =>
                "NETWORK " + prettyString(u.getNetwork.getId)
            case u: Update if u.hasPort =>
                "PORT " + prettyString(u.getPort.getId)
            case u: Update if u.hasPortGroup =>
                "PORTGROUP " + prettyString(u.getPortGroup.getId)
            case u: Update if u.hasRoute =>
                "ROUTE " + prettyString(u.getRoute.getId)
            case u: Update if u.hasRouter =>
                "ROUTER " + prettyString(u.getRouter.getId)
            case u: Update if u.hasRule =>
                "RULE " + prettyString(u.getRule.getId)
            case u: Update if u.hasTunnelZone =>
                "TUNNELZONE " + prettyString(u.getTunnelZone.getId)
            case u: Update if u.hasVtep =>
                "VTEP " + prettyString(u.getVtep.getId)
            case u: Update if u.hasVtepBinding =>
                "VTEPBINDING " + prettyString(u.getVtepBinding.getId)
            case u: Update => ""
        }) + "\n" + update.toString

    private def prettyString(deletion: Deletion): String =
        "DELETION: " +
            prettyString(deletion.getType) + " " +
            prettyString(deletion.getId)

    private def prettyString(id: Commons.UUID): String =
        UUIDUtil.fromProto(id).toString

    private def prettyString(t: Topology.Type): String =
        t.getValueDescriptor.getName + "(" + t.getNumber + ")"

    private def prettyString(id: ID): String = id match {
        case uuid: ID if uuid.hasUuid => prettyString(uuid.getUuid)
        case strid: ID if strid.hasStrId => strid.getStrId
        case other => "<UNKNOWN ID>"
    }

    private def prettyString(s: String): String = s


}

/** Configuration for the topology API minion */
@ConfigGroup("snoopy")
trait TopologySnoopyConfig extends MinionConfig[TopologySnoopy] {
    import TopologySnoopyConfig._

    @ConfigBool(key = "enabled", defaultValue = true)
    override def isEnabled: Boolean

    @ConfigString(key = "with",
                  defaultValue =
                      "org.midonet.brain.services.topology.TopologySnoopy")
    override def minionClass: String

    /** Host ip */
    @ConfigString(key = "host", defaultValue = DEFAULT_HOST)
    def getHost: String

    /** Port for plain socket connections */
    @ConfigInt(key = "port", defaultValue = DEFAULT_PORT)
    def getPort: Int

    /** Websocket url path, if any */
    @ConfigString(key = "ws_path", defaultValue = DEFAULT_WS_PATH)
    def getWsPath: String
}

object TopologySnoopyConfig {
    final val DEFAULT_HOST = "localhost"
    final val DEFAULT_PORT = 8081
    final val DEFAULT_WS_PATH = ""
}
