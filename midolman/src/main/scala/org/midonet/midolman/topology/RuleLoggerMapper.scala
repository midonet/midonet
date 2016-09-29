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

package org.midonet.midolman.topology

import java.util.UUID

import com.google.protobuf.GeneratedMessage

import rx.Observable

import org.midonet.cluster.models.Commons.LogEvent
import org.midonet.cluster.models.Topology.LoggingResource.Type
import org.midonet.cluster.models.Topology.{LoggingResource, RuleLogger}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.{RuleLogger => SimRuleLogger}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

class RuleLoggerMapper(id: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper(classOf[SimRuleLogger], id, vt)
            with MidolmanLogging {

    override def logSource: String = "org.midonet.devices.rule-logger"
    override def logMark: String = s"rule-logger:$id"

    private val logResTracker =
        new StoreObjectReferenceTracker(vt, classOf[LoggingResource], log)

    private var ruleLogger: RuleLogger = null
    private val ruleLoggerObservable =
        vt.store.observable(classOf[RuleLogger], id)
            .observeOn(vt.vtScheduler)
            .doOnCompleted(makeAction0(ruleLoggerDeleted()))
            .doOnNext(makeAction1(ruleLoggerUpdated))

    private var simRuleLogger: SimRuleLogger = null

    // NB: The order of merged observables is important. LogResTracker's
    // observable must be merged before ruleLoggerObservable is merged, so that
    // the LogResource update isn't dropped.
    override val observable: Observable[SimRuleLogger] =
        Observable.merge(logResTracker.refsObservable, ruleLoggerObservable)
            .observeOn(vt.vtScheduler)
            .filter(makeFunc1(isReady))
            .map[SimRuleLogger](makeFunc1(build))
            .distinctUntilChanged()

    private def build(gm: GeneratedMessage): SimRuleLogger = {
        assertThread()
        val lr = logResTracker.currentRefs.head._2
        val logAcceptEvents =
            lr.getEnabled && (ruleLogger.getEvent == LogEvent.ACCEPT ||
                              ruleLogger.getEvent == LogEvent.ALL)
        val logDropEvents =
            lr.getEnabled && (ruleLogger.getEvent == LogEvent.DROP ||
                              ruleLogger.getEvent == LogEvent.ALL)

        simRuleLogger = lr.getType match {
            case Type.FILE =>
                new SimRuleLogger(
                    id, logAcceptEvents, logDropEvents, vt.ruleLogEventChannel)
        }

        if (!vt.ruleLogEventChannel.isRunning) {
            try vt.ruleLogEventChannel.startAsync() catch {
                case ex: IllegalStateException =>
                    // This is a bit suspicious, but it might just be taking a
                    // while to start up.
                    log.warn("RuleLogEventChannel has been started, but is " +
                             "not yet running.")
            }
        }

        log.debug(s"Emitting $simRuleLogger")
        simRuleLogger
    }

    private def isReady(gm: GeneratedMessage): Boolean = {
        assertThread()
        logResTracker.areRefsReady
    }

    private def ruleLoggerDeleted(): Unit = {
        assertThread()
        log.debug(s"RuleLogger $id deleted")
    }

    private def ruleLoggerUpdated(rl: RuleLogger): Unit = {
        assertThread()
        log.debug(s"RuleLogger $id updated")
        logResTracker.requestRefs(rl.getLoggingResourceId.asJava)
        ruleLogger = rl
    }
}
