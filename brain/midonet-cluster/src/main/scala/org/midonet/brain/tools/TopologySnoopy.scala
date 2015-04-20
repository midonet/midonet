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

package org.midonet.brain.tools


import org.midonet.brain.BrainConfig

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import org.slf4j.LoggerFactory
import rx.Subscriber

import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rpc.Commands.{ResponseType, Response}
import org.midonet.cluster.services.topology.client.ClientSession
import org.midonet.cluster.util.UUIDUtil

/**
 * A topology API client that establishes a client session to the
 * Topology Service and subscribes to all available topology objects,
 * dumping the updates and deletions to a log.
 */
class TopologySnoopy @Inject()(val cfg: BrainConfig)
    extends AbstractService {
    private val log = LoggerFactory.getLogger(classOf[TopologySnoopy])

    private final val client = this
    private val session = new ClientSession(cfg.snoopy.host, cfg.snoopy.port,
        cfg.snoopy.wsPath)

    private val types: Array[Topology.Type] = Topology.Type.values()

    @Override
    def doStart(): Unit = {
        log.info("Starting Topology Snoopy")
        try {
            types foreach session.watchAll
            session.connect().subscribe(new Subscriber[Response] {
                override def onCompleted(): Unit = {
                    log.info("No more updates available")
                    this.unsubscribe()
                    client.stopAsync()
                }
                override def onError(e: Throwable): Unit = {
                    log.warn("Update flow interrupted", e)
                    this.unsubscribe()
                    client.stopAsync()
                }
                override def onNext(msg: Response): Unit = {
                    log.info(prettyString(msg))
                }
            })
            log.info("Topology Snoopy ready")
            notifyStarted()
        } catch {
            case e: Throwable =>
                log.warn("Topology Snoopy failed to start")
                notifyFailed(e)
        }
    }

    @Override
    def doStop(): Unit = {
        log.info("Stopping Topology Snoopy")
        types foreach {t => session.unwatchAll(t)}
        session.terminate()
        try {
            session.awaitTermination(10.second)
            log.info("Topology Snoopy stopped")
            notifyStopped()
        } catch {
            case e: TimeoutException => try {
                session.terminateNow()
                session.awaitTermination(10.second)
                log.info("Topology Snoopy stopped")
            } catch {
                case e: Throwable =>
                    log.warn("Topology Snoopy termination failed", e)
                    notifyFailed(e)
            }
            case e: Throwable =>
                log.warn("Topology Snoopy termination failed", e)
                notifyFailed(e)
        }
    }

    def awaitTermination(atMost: Duration): Option[Throwable] =
        session.awaitTermination(atMost)

    private def prettyString(msg: Response): String = msg match {
        case r: Response if r.getType == ResponseType.UPDATE =>
            prettyStringUpdate(r)
        case r: Response if r.getType == ResponseType.DELETION =>
            prettyStringDeletion(r)
        case r: Response if r.getType == ResponseType.SNAPSHOT =>
            prettyStringSnapshot(r)
        case r: Response if r.getType == ResponseType.REDIRECT =>
            prettyStringRedirect(r)
        case r: Response if r.getType == ResponseType.ERROR =>
            prettyStringError(r)
        case other => "UNEXPECTED MESSAGE: " + other.toString
    }

    private def prettyStringUpdate(update: Response): String =
        "UPDATE: " + prettyString(update.getObjType) +
            " " + prettyString(update.getObjId) +
            "\n" + update.getUpdate.toString

    private def prettyStringDeletion(deletion: Response): String =
        "DELETION: " + prettyString(deletion.getObjType) +
            " " + prettyString(deletion.getObjId)

    private def prettyStringSnapshot(snapshot: Response): String =
        "SNAPSHOT: " + prettyString(snapshot.getObjType) +
            "\n" + snapshot.getSnapshot.toString

    private def prettyStringRedirect(redirect: Response): String =
        "REDIRECT: " + prettyString(redirect.getReqId) +
            " -> " + prettyString(redirect.getRedirect.getOriginalReqId)

    private def prettyStringError(error: Response): String =
        "ERROR: " + prettyString(error.getReqId) +
            "\n" + error.getInfo.getMsg

    private def prettyString(id: Commons.UUID): String =
        UUIDUtil.fromProto(id).toString

    private def prettyString(t: Topology.Type): String =
        t.getValueDescriptor.getName + "(" + t.getNumber + ")"


}

