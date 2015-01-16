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

package org.midonet.brain.services.topology

import java.util.concurrent.TimeoutException

import com.google.inject.Inject
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rpc.Commands.Response
import org.midonet.cluster.services.topology.client.ClientSession

import org.slf4j.LoggerFactory

import org.midonet.brain.{MinionConfig, ClusterMinion}
import org.midonet.config._
import rx.{Observer, Subscription}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Topology api service skeleton
 */
class TopologySnoopy @Inject()(val cfg: TopologySnoopyConfig)
    extends ClusterMinion {
    private val log = LoggerFactory.getLogger(classOf[TopologySnoopy])

    private val session = new ClientSession(cfg.getHost, cfg.getPort,
                                            cfg.getWsPath)
    private var subscription: Subscription = null

    private val types: List[Topology.Type] = List(Topology.Type.ROUTER,
                                                  Topology.Type.NETWORK,
                                                  Topology.Type.PORT)

    @Override
    def doStart(): Unit = {
        log.info("Starting the Topology Snoopy client")
        try {
            types foreach {t => session.watchAll(t)}
            subscription = session.connect().subscribe(new Observer[Response] {
                override def onCompleted(): Unit = {
                    log.info("No more updates available")
                }
                override def onError(e: Throwable): Unit = {
                    log.error("Update flow interrupted", e)
                }
                override def onNext(msg: Response): Unit = msg match {
                    case r: Response if r.hasUpdate =>
                        log.info("UPDATE: " + r)
                    case r: Response if r.hasDeletion =>
                        log.info("DELETE: " + r)
                    case other =>
                        log.warn("UNKNOWN: " + other)
                }
                log.info("Client started")
                notifyStarted()
            })
        } catch {
            case e: Throwable =>
                log.warn("Client start failed")
                notifyFailed(e)
        }
    }

    @Override
    def doStop(): Unit = {
        log.info("Stopping the Topology Snoopy Client")
        types foreach {t => session.unwatchAll(t)}
        session.terminate()
        try {
            session.awaitTermination(10.second)
            log.info("Service stopped")
            notifyStopped()
        } catch {
            case e: TimeoutException => try {
                session.terminateNow()
                session.awaitTermination(10.second)
            } catch {
                case e: Throwable =>
                    log.warn("Service termination failed", e)
                    notifyFailed(e)
            }
            case e: Throwable =>
                log.warn("Service termination failed", e)
                notifyFailed(e)
        }
    }

    def awaitTermination(atMost: Duration): Option[Throwable] =
        session.awaitTermination(atMost)
}

/** Configuration for the topology API minion */
@ConfigGroup("snoopy")
trait TopologySnoopyConfig extends MinionConfig[TopologySnoopy] {
    import TopologySnoopyConfig._

    val configGroup = "snoopy"

    @ConfigBool(key = "enabled", defaultValue = true)
    override def isEnabled: Boolean

    @ConfigString(key = "with")
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
