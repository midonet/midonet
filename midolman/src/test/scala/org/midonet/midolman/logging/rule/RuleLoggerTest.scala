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

package org.midonet.midolman.logging.rule

import java.io.File
import java.util.UUID

import scala.collection.JavaConversions._
import scala.util.Random

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterAll

import org.midonet.logging.rule.RuleLogEventBinarySerialization.{decodeMetadata, encodeMetadata}
import org.midonet.logging.rule.{DeserializedRuleLogEvent, RuleLogEventBinaryDeserializer}
import org.midonet.midolman.rules.{LiteralRule, Rule}
import org.midonet.midolman.simulation.{Chain, PacketContext, RuleLogger}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}
import org.midonet.util.MidonetEventually

class RuleLoggerTest extends MidolmanSpec
                             with BeforeAndAfterAll
                             with MidonetEventually {

    val logDirPath = "/tmp/rule-logger-test"
    val logFilePath = logDirPath + "/rule-log.blg"
    private val logDir: File = new File(logDirPath)
    private val logFile: File = new File(logFilePath)
    private val eventChannel = DisruptorRuleLogEventChannel(256, logFilePath)

    private val rand = new Random

    override protected def beforeAll(): Unit = {
        super.beforeAll()
        logDir.mkdir()
    }

    override protected def afterAll(): Unit = {
        super.afterAll()
        FileUtils.deleteDirectory(logDir)
    }

    protected override def beforeTest(): Unit = {
        eventChannel.start()
    }

    protected override def afterTest(): Unit = {
        eventChannel.stop()
        logFile.delete()
    }

    feature("FileRuleLogger") {
        scenario("Logs events to file") {
            val (chain, rule, logger) = makeLogger()
            val ctx = makePktCtx()
            logger.logAccept(ctx, chain, rule)
            eventChannel.flush()

            checkFile(66)

            val deserializer = makeDeserializer
            deserializer.hasNext shouldBe true
            val event = deserializer.next()
            checkEvent(event, ctx.wcmatch, chain, rule, "ACCEPT")

            deserializer.hasNext shouldBe false
        }

        scenario("Logs event metadata") {
            val (chain, rule, logger) =
                makeLogger(metadata = Seq("key1" -> "val1", "key2" -> "val2"))
            val ctx = makePktCtx()
            logger.logAccept(ctx, chain, rule)
            eventChannel.flush()

            val deserializer = makeDeserializer
            deserializer.hasNext shouldBe true
            val event = deserializer.next()
            checkEvent(event, ctx.wcmatch, chain, rule, "ACCEPT")
            deserializer.hasNext shouldBe false
        }

        scenario("Logs IPv6 addresses") {
            val (chain, rule, logger) = makeLogger()
            val ctx = makePktCtx(srcIp = IPv6Addr.random,
                                 dstIp = IPv6Addr.random)
            logger.logDrop(ctx, chain, rule)
            eventChannel.flush()

            val deserializer = makeDeserializer
            deserializer.hasNext shouldBe true
            val event = deserializer.next()
            checkEvent(event, ctx.wcmatch, chain, rule, "DROP")
            deserializer.hasNext shouldBe false
        }

        scenario("Logs multiple events") {
            val (chain1, rule, logger) = makeLogger(
                metadata = Seq("11" -> "eleven", "12" -> "twelve"))
            val chain2 = makeChain(UUID.randomUUID(),
                                   Seq("13" -> "thirteen", "14" -> "fourteen"))
            val ctx1 = makePktCtx()
            val ctx2 = makePktCtx()
            val ctx3 = makePktCtx()

            logger.logAccept(ctx1, chain1, rule)
            logger.logDrop(ctx2, chain2, rule)
            logger.logDrop(ctx3, chain2, rule)
            eventChannel.flush()

            val deserializer = makeDeserializer

            deserializer.hasNext shouldBe true
            val event1 = deserializer.next()
            checkEvent(event1, ctx1.wcmatch, chain1, rule, "ACCEPT")

            deserializer.hasNext shouldBe true
            val event2 = deserializer.next()
            checkEvent(event2, ctx2.wcmatch, chain2, rule, "DROP")

            deserializer.hasNext shouldBe true
            val event3 = deserializer.next()
            checkEvent(event3, ctx3.wcmatch, chain2, rule, "DROP")

            deserializer.hasNext shouldBe false
        }
    }

    private def makeDeserializer =
        new RuleLogEventBinaryDeserializer(logFile.getPath)

    private def checkFile(size: Int): Unit = {
        logFile.exists() shouldBe true
        logFile.length().toInt shouldBe size
    }

    private def checkEvent(e: DeserializedRuleLogEvent, fm: FlowMatch,
                           chain: Chain, rule: Rule, result: String): Unit = {
        e.srcIp shouldBe fm.getNetworkSrcIP
        e.dstIp shouldBe fm.getNetworkDstIP
        e.srcPort shouldBe fm.getSrcPort
        e.dstPort shouldBe fm.getDstPort
        e.nwProto shouldBe fm.getNetworkProto
        e.result shouldBe result
        (System.currentTimeMillis - e.time) should be < 5000L
        e.chainId shouldBe chain.id
        e.ruleId shouldBe rule.id
        e.metadata should contain theSameElementsAs
            decodeMetadata(chain.metadata, chain.metadata.length)
    }

    private def makeLogger(logAccept: Boolean = true,
                   logDrop: Boolean = true,
                   metadata: Seq[(String, String)] = Seq())
    : (Chain, Rule, RuleLogger) = {
        val chain = makeChain(UUID.randomUUID(), metadata)
        val rule = new LiteralRule
        rule.id = UUID.randomUUID()
        val logger = new RuleLogger(UUID.randomUUID(), logAccept,
                                    logDrop, eventChannel)
        (chain, rule, logger)
    }

    private def makeChain(id: UUID, metadata: Seq[(String, String)]): Chain = {
        new Chain(id, List(), Map[UUID, Chain](), s"chain-$id",
                  encodeMetadata(metadata), Seq())
    }

    private def makePktCtx(nwProto: Byte = rand.nextInt.toByte,
                           srcIp: IPAddr = IPv4Addr.random,
                           dstIp: IPAddr = IPv4Addr.random,
                           srcPort: Int = rand.nextInt(65535),
                           dstPort: Int = rand.nextInt(65535))
    : PacketContext = {
        val ctx = new PacketContext
        ctx.wcmatch.setNetworkProto(nwProto)
        ctx.wcmatch.setNetworkSrc(srcIp)
        ctx.wcmatch.setNetworkDst(dstIp)
        ctx.wcmatch.setSrcPort(srcPort)
        ctx.wcmatch.setDstPort(dstPort)
        ctx
    }
}
