/*
 * Copyright 2016 Midokura SARL
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
import java.nio.file.Files
import java.util.{Properties, UUID}

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.util.Random

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner

import org.midonet.logging.rule.RuleLogEventBinarySerialization.{decodeMetadata, encodeMetadata}
import org.midonet.logging.rule.{DeserializedRuleLogEvent, RuleLogEventBinaryDeserializer}
import org.midonet.midolman.config.RuleLoggingConfig
import org.midonet.midolman.rules.{LiteralRule, Rule}
import org.midonet.midolman.simulation.{Chain, PacketContext, RuleLogger}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}
import org.midonet.util.{MidonetEventually, MockUnixClock, UnixClock}
import org.midonet.util.logging.RollingOutputStream

@RunWith(classOf[JUnitRunner])
class RuleLoggerTest extends MidolmanSpec
                             with BeforeAndAfterAll
                             with MidonetEventually {

    val logDirPath = "/tmp/rule-logger-test"
    val logFileName = "rule-logger-test.rlg"
    val logFilePath = s"$logDirPath/$logFileName"
    private val logDir: File = new File(logDirPath)
    private val logFile: File = new File(logFilePath)
    private val ruleLogConfig = new RuleLoggingConfig(null, null) {
        override def compress = true
        override def logFileName: String = RuleLoggerTest.this.logFileName
        override def maxFiles: Int = 3
        override def logDirectory: String = logDirPath
        override def rotationFrequency: String = "1kb"
    }

    private var eventChannel: DisruptorRuleLogEventChannel = null
    private var eventHandler: FileRuleLogEventHandler = null
    private var handlerClock: MockUnixClock = null

    private val rand = new Random

    private var savedProperties: Properties = null

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

    protected override def beforeTest(): Unit = {
        eventChannel = DisruptorRuleLogEventChannel(256, ruleLogConfig)
        eventHandler =
            getFieldValue[FileRuleLogEventHandler](eventChannel, "eventHandler")
        handlerClock = getFieldValue[MockUnixClock](eventHandler, "clock")

        handlerClock.time = System.currentTimeMillis()
        eventChannel.startAsync()
        eventChannel.awaitRunning()
    }

    protected override def afterTest(): Unit = {
        eventChannel.stopAsync()
        eventChannel.awaitTerminated()
        logDir.listFiles.foreach(_.delete())
    }

    feature("FileRuleLogger") {
        scenario("Logs events to file") {
            val (chain, rule, logger) = makeLogger()
            val ctx = makePktCtx()
            logger.logAccept(ctx, chain, rule)
            eventChannel.flush()

            checkFile(82)

            val deserializer = makeDeserializer
            deserializer.hasNext shouldBe true
            val event = deserializer.next()
            checkEvent(event, ctx.wcmatch, chain, rule, logger.id, "ACCEPT")

            deserializer.hasNext shouldBe false
        }

        scenario("Logs event metadata") {
            val (chain, rule, logger) = makeLogger(metadata = defaultMetadata())
            val ctx = makePktCtx()
            logger.logAccept(ctx, chain, rule)
            eventChannel.flush()

            val deserializer = makeDeserializer
            deserializer.hasNext shouldBe true
            val event = deserializer.next()
            checkEvent(event, ctx.wcmatch, chain, rule, logger.id, "ACCEPT")
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
            checkEvent(event, ctx.wcmatch, chain, rule, logger.id, "DROP")
            deserializer.hasNext shouldBe false
        }

        scenario("Logs multiple events") {
            val (chain1, rule, logger) =
                makeLogger(metadata = defaultMetadata())
            val chain2 = makeChain(UUID.randomUUID(), defaultMetadata())
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
            checkEvent(event1, ctx1.wcmatch, chain1, rule, logger.id, "ACCEPT")

            deserializer.hasNext shouldBe true
            val event2 = deserializer.next()
            checkEvent(event2, ctx2.wcmatch, chain2, rule, logger.id, "DROP")

            deserializer.hasNext shouldBe true
            val event3 = deserializer.next()
            checkEvent(event3, ctx3.wcmatch, chain2, rule, logger.id, "DROP")

            deserializer.hasNext shouldBe false
        }

        scenario("Handles log rotation") {
            // 8 byte header + (170 bytes per event * 6 events) = 858. This is
            // greater than 854 (1024 - 170), so there should be five events per
            // 1024-byte log file.
            val headerSize = 8
            val recordSize = 170
            val (chain, rule, logger) =
                makeLogger(metadata = defaultMetadata())

            def logEvents(numEvents: Int) =
                this.logEvents(numEvents, logger, chain, rule)

            logEvents(5)

            val gzLog1 = gzLog(1)
            val gzLog2 = gzLog(2)
            val gzLog3 = gzLog(3)

            // Only six events, so there should be no rotation yet.
            gzLog1.exists() shouldBe false
            checkFile(headerSize + 5 * recordSize)

            // One more should push it over.
            logEvents(1)

            gzLog1.exists() shouldBe true
            checkFile(headerSize + recordSize)

            // Rotate twice more.
            logEvents(11)
            gzLog2.exists() shouldBe true
            gzLog3.exists() shouldBe true
            checkFile(headerSize + 2 * recordSize)

            // Rotate once more. Max files is three, so a fourth file should not
            // be created.
            val gzLog1Bytes = Files.readAllBytes(gzLog1.toPath)
            val gzLog2Bytes = Files.readAllBytes(gzLog2.toPath)

            logEvents(6)
            gzLog(4).exists shouldBe false
            Files.readAllBytes(gzLog2.toPath) shouldBe gzLog1Bytes
            Files.readAllBytes(gzLog3.toPath) shouldBe gzLog2Bytes
            checkFile(headerSize + 3 * recordSize)
        }

        scenario("Recovers from exception after one minute.") {
            val headerSize = 8
            val recordSize = 170
            val (chain, rule, logger) =
                makeLogger(metadata = defaultMetadata())

            def logEvents(numEvents: Int = 1, flush: Boolean = true) =
                this.logEvents(numEvents, logger, chain, rule, flush)

            logEvents(1)

            // Close the output stream to trigger an exception on the next write
            val os = getFieldValue[RollingOutputStream](eventHandler, "os")
            os.close()

            checkFile(headerSize + recordSize)

            // Should cause an exception, and not log the event.
            // Don't flush, because the call to flush() is outside the event
            // handler loop and raises an exception that won't get caught.
            logEvents(flush = false)
            checkFile(headerSize + recordSize)

            // rotateLogs() should create a new output stream.
            eventHandler.rotateLogs()
            checkFile(headerSize)

            // The cause of the exception is fixed, but the event handler should
            // wait sixty seconds before trying again.
            logEvents(3)

            // Those events shouldn't have been logged.
            checkFile(headerSize)

            // Advance time sixty seconds to start logging again.
            handlerClock.time += 60 * 1000
            logEvents(3)
            checkFile(headerSize + 3 * recordSize)
        }
    }

    private def getFieldValue[T](obj: AnyRef, fieldName: String): T = {
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

    private def logEvents(numEvents: Int, logger: RuleLogger,
                          chain: Chain, rule: Rule,
                          flush: Boolean = true): Unit = {
        for (i <- 1 to numEvents) {
            val ctx = makePktCtx()
            logger.logAccept(ctx, chain, rule)
        }
        if (flush)
            eventChannel.flush()
    }

    private def gzLog(i: Int) = new File(s"$logFilePath.$i.gz")

    private def makeDeserializer =
        new RuleLogEventBinaryDeserializer(logFilePath)

    private def checkFile(size: Int): Unit = {
        logFile.exists() shouldBe true
        logFile.length().toInt shouldBe size
    }

    private def checkEvent(e: DeserializedRuleLogEvent, fm: FlowMatch,
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

    private def defaultMetadata(): Seq[(String, String)] = {
        Seq("firewall_id" -> UUID.randomUUID().toString,
            "tenant_id" -> UUID.randomUUID().toString.replace("-", ""))
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
