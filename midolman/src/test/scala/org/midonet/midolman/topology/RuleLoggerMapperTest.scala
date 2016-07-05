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

import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.{Observable, Subscription}

import org.midonet.cluster.models.Commons.LogEvent
import org.midonet.cluster.models.Topology.{LoggingResource, RuleLogger}
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.simulation.{FileRuleLogger, Chain => SimChain, RuleLogger => SimRuleLogger}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.TestAwaitableObserver
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class RuleLoggerMapperTest extends MidolmanSpec with TopologyBuilder
                                   with TopologyMatchers with MidonetEventually {
    private val timeout: Duration = 1 second
    private var vt: VirtualTopology = _

    override protected def beforeTest(): Unit = {
        super.beforeTest()
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    feature("RuleLoggerMapper") {
        scenario("Publishes existing RuleLogger on startup.") {
            val ch = createChain(name = Some("chain"))
            vt.store.create(ch)

            val lr = createLoggingResource()
            vt.store.create(lr)

            val rl = createRuleLogger(lr.getId.asJava, ch.getId.asJava,
                                      fileName = Some("log.log"))
            vt.store.create(rl)

            val obs = createChainMapperAndObserver(ch.getId.asJava)
            obs.awaitOnNext(1, timeout)
            val simChain = obs.getOnNextEvents.get(0)
            simChain.ruleLoggers.size shouldBe 1
            checkFileRuleLogger(simChain.ruleLoggers.head,
                                rl.getId.asJava, "log.log")
        }

        scenario("Publishes new RuleLogger") {
            val ch = createChain(name = Some("chain"))
            vt.store.create(ch)

            val obs = createChainMapperAndObserver(ch.getId.asJava)
            obs.awaitOnNext(1, timeout)
            var simChain = obs.getOnNextEvents.get(0)
            simChain.ruleLoggers shouldBe empty


            val lr = createLoggingResource()
            vt.store.create(lr)
            val rl = createRuleLogger(lr.getId.asJava, ch.getId.asJava,
                                      logEvent = LogEvent.DROP,
                                      fileName = Some("log.log"))
            vt.store.create(rl)
            obs.awaitOnNext(2, timeout)

            simChain = obs.getOnNextEvents.get(1)
            simChain.ruleLoggers.size shouldBe 1
            checkFileRuleLogger(simChain.ruleLoggers.head,
                                rl.getId.asJava, "log.log",
                                logAccept = false)
        }

        scenario("Publishes updates to LoggingResource's 'enabled' property") {
            val ch = createChain()
            vt.store.create(ch)
            val obs = createChainMapperAndObserver(ch.getId.asJava)

            var lr = createLoggingResource()
            vt.store.create(lr)

            val rl = createRuleLogger(lr.getId.asJava, ch.getId.asJava,
                                      fileName = Some("log.log"),
                                      logEvent = LogEvent.ACCEPT)
            vt.store.create(rl)

            val rl2 = createRuleLogger(lr.getId.asJava, ch.getId.asJava,
                                       fileName = Some("log2.log"),
                                       logEvent = LogEvent.DROP)
            vt.store.create(rl2)

            obs.awaitOnNext(3, timeout)
            var simChain = obs.getOnNextEvents.get(2)
            simChain.ruleLoggers.size shouldBe 2

            // Disable LoggingResource
            lr = vt.store.get(classOf[LoggingResource], lr.getId).await()
            vt.store.update(lr.toBuilder.setEnabled(false).build())
            obs.awaitOnNext(5, timeout)
            simChain = obs.getOnNextEvents.get(4)
            for (rl <- simChain.ruleLoggers) {
                rl.logAcceptEvents shouldBe false
                rl.logDropEvents shouldBe false
            }

            // Reenable it.
            vt.store.update(lr)
            obs.awaitOnNext(7, timeout)
            simChain = obs.getOnNextEvents.get(6)

            val simRl = simChain.ruleLoggers.find(_.id == rl.getId.asJava).get
            simRl.logAcceptEvents shouldBe true
            simRl.logDropEvents shouldBe false
            val simRl2 = simChain.ruleLoggers.find(_.id == rl2.getId.asJava).get
            simRl2.logAcceptEvents shouldBe false
            simRl2.logDropEvents shouldBe true
        }

        scenario("Completes on RuleLogger deletion") {
            val ch = createChain(name = Some("chain"))
            vt.store.create(ch)
            val lr = createLoggingResource()
            vt.store.create(lr)
            val rl = createRuleLogger(lr.getId.asJava, ch.getId.asJava,
                                      fileName = Some("log.log"))
            vt.store.create(rl)
            val rl2 = createRuleLogger(lr.getId.asJava, ch.getId.asJava,
                                       fileName = Some("log2.log"))
            vt.store.create(rl2)

            val obs = createChainMapperAndObserver(ch.getId.asJava)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0).ruleLoggers.size shouldBe 2

            vt.store.delete(classOf[RuleLogger], rl.getId)
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1).ruleLoggers.size shouldBe 1

            vt.store.delete(classOf[RuleLogger], rl2.getId)
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.get(2).ruleLoggers.size shouldBe 0
        }
    }

    private def checkFileRuleLogger(
        rl: SimRuleLogger, id: UUID, fileName: String,
        logDir: String = FileRuleLogger.DefaultLogDir,
        logAccept: Boolean = true, logDrop: Boolean = true): Unit = rl match {
        case frl: FileRuleLogger =>
            frl.id shouldBe id
            frl.logAcceptEvents shouldBe logAccept
            frl.logDropEvents shouldBe logDrop
            frl.fileName shouldBe fileName
            frl.logDir shouldBe logDir
    }

    private def createChainMapperAndObserver(chainId: UUID)
    : TestAwaitableObserver[SimChain] = {
        val mapper = new ChainMapper(chainId, vt, mutable.Map())
        val obs = new TestAwaitableObserver[SimChain]
        Observable.create(mapper).subscribe(obs)
        obs
    }
}
