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

import org.midonet.cluster.models.Topology.{Chain, LoggingResource}
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.simulation.{FileRuleLogger, Chain => SimChain, RuleLogger => SimRuleLogger}
import org.midonet.util.reactivex.TestAwaitableObserver

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
            val ch = createChain()
            vt.store.create(ch)

            val lr = createLoggingResource()
            vt.store.create(lr)

            val rl = createRuleLogger(lr.getId.asJava, ch.getId.asJava,
                                      fileName = Some("log.log"))
            vt.store.create(rl)

            val tc = vt.store.get(classOf[Chain], ch.getId)


            val obs = createChainMapperAndObserver(ch.getId.asJava)
            obs.awaitOnNext(1, timeout)
            val simChain = obs.getOnNextEvents.get(0)
            simChain.ruleLoggers.size shouldBe 1
            simChain.ruleLoggers.head match {
                case frl: FileRuleLogger =>
                    frl.id shouldBe rl.getId.asJava
                    frl.logAcceptEvents shouldBe true
                    frl.logDropEvents shouldBe true
                    frl.fileName shouldBe "log.log"
                    frl.logDir shouldBe FileRuleLogger.defaultLogDir
            }
        }
    }

    private def createChainMapperAndObserver(chainId: UUID)
    : TestAwaitableObserver[SimChain] = {
        val mapper = new ChainMapper(chainId, vt, mutable.Map())
        val obs = new TestAwaitableObserver[SimChain]
        mapper.observable.subscribe(obs)
        obs
    }
}
