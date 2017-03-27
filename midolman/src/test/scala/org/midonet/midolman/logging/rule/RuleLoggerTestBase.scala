/*
 * Copyright 2017 Midokura SARL
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
import java.lang.reflect.Field
import java.util.{Properties, UUID}

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.util.Random

import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FeatureSpec, Matchers}

import org.midonet.logging.rule.RuleLogEventBinarySerialization.{decodeMetadata, encodeMetadata}
import org.midonet.logging.rule.{DeserializedRuleLogEvent, RuleLogEventBinaryDeserializer}
import org.midonet.midolman.config.RuleLoggingConfig
import org.midonet.midolman.rules.{LiteralRule, Rule}
import org.midonet.midolman.simulation.{Chain, PacketContext, RuleLogger}
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.util.{MidonetEventually, MockUnixClock, UnixClock}

class RuleLoggerTestBase extends FeatureSpec
                                 with BeforeAndAfter
                                 with BeforeAndAfterAll
                                 with Matchers
                                 with MidonetEventually {

    val logDirPath = "/tmp/rule-logger-test"
    val logFileName = "rule-logger-test.rlg"
    val logFilePath = s"$logDirPath/$logFileName"
    private val logDir: File = new File(logDirPath)
    private val logFile: File = new File(logFilePath)
    private val ruleLogConfig = new RuleLoggingConfig(null, null) {
        override def compress = true
        override def logFileName: String = RuleLoggerTestBase.this.logFileName
        override def maxFiles: Int = 3
        override def logDirectory: String = logDirPath
        override def rotationFrequency: String = "1kb"
    }

    protected var eventChannel: DisruptorRuleLogEventChannel = _
    protected var eventHandler: FileRuleLogEventHandler = _
    protected var handlerClock: MockUnixClock = _

    private val rand = new Random

    private var savedProperties: Properties = _

    override protected def beforeAll(): Unit = {
        super.beforeAll()
        logDir.mkdir()
        savedProperties = System.getProperties
        System.setProperty(UnixClock.USE_MOCK_CLOCK_PROPERTY, "yes")
    }

    override protected def afterAll(): Unit = {
        super.afterAll()
        FileUtils.deleteDirectory(logDir)
        System.setProperties(savedProperties)
    }

    before {
        eventChannel = DisruptorRuleLogEventChannel(256, ruleLogConfig)
        eventHandler =
            getFieldValue[FileRuleLogEventHandler](eventChannel, "eventHandler")
        handlerClock = getFieldValue[MockUnixClock](eventHandler, "clock")

        handlerClock.time = System.currentTimeMillis()
        eventChannel.startAsync()
        eventChannel.awaitRunning()
    }

    after {
        eventChannel.stopAsync()
        eventChannel.awaitTerminated()
        logDir.listFiles.foreach(_.delete())
    }

    protected def getFieldValue[T](obj: AnyRef, fieldName: String): T = {
        val f = getField(obj.getClass, fieldName)
        f.setAccessible(true)
        f.get(obj).asInstanceOf[T]
    }

    @tailrec
    private def getField(clazz: Class[_], fieldName: String): Field = {
        clazz.getDeclaredFields.find(_.getName == fieldName) match {
            case Some(f) => f
            case None => getField(clazz.getSuperclass, fieldName)
        }
    }

    protected def logEvents(numEvents: Int, logger: RuleLogger,
                          chain: Chain, rule: Rule,
                          flush: Boolean = true): Unit = {
        for (_ <- 1 to numEvents) {
            val ctx = makePktCtx()
            logger.logAccept(ctx, chain, rule)
        }
        if (flush)
            eventChannel.flush()
    }

    protected def gzLog(i: Int) = new File(s"$logFilePath.$i.gz")

    protected def makeDeserializer =
        new RuleLogEventBinaryDeserializer(logFilePath)

    protected def checkFile(size: Int): Unit = {
        logFile.exists() shouldBe true
        logFile.length().toInt shouldBe size
    }

    protected def checkEvent(e: DeserializedRuleLogEvent, fm: FlowMatch,
                             chain: Chain, rule: Rule,
                             loggerId: UUID, result: String): Unit = {
        e.srcIp shouldBe fm.getNetworkSrcIP
        e.dstIp shouldBe fm.getNetworkDstIP
        e.srcPort shouldBe fm.getSrcPort
        e.dstPort shouldBe fm.getDstPort
        e.nwProto shouldBe fm.getNetworkProto
        e.result shouldBe result
        (System.currentTimeMillis - e.time) should be < 5000L
        e.loggerId shouldBe loggerId
        e.chainId shouldBe chain.id
        e.ruleId shouldBe rule.id
        e.metadata should contain theSameElementsAs
        decodeMetadata(chain.metadata, chain.metadata.length)
    }

    protected def makeLogger(logAccept: Boolean = true,
                             logDrop: Boolean = true,
                             metadata: Seq[(String, String)] = Seq())
    : (Chain, Rule, RuleLogger) = {
        val chain = makeChain(UUID.randomUUID(), metadata)
        val rule = new LiteralRule
        rule.id = UUID.randomUUID()
        val logger = RuleLogger(UUID.randomUUID(), logAccept,
                                logDrop, eventChannel)
        (chain, rule, logger)
    }

    protected def makeChain(id: UUID,
                            metadata: Seq[(String, String)]): Chain = {
        new Chain(id, List(), Map[UUID, Chain](), s"chain-$id",
                  encodeMetadata(metadata), Seq())
    }

    protected def defaultMetadata(): Seq[(String, String)] = {
        Seq("firewall_id" -> UUID.randomUUID().toString,
            "tenant_id" -> UUID.randomUUID().toString.replace("-", ""))
    }

    protected def makePktCtx(nwProto: Byte = rand.nextInt.toByte,
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
