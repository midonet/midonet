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

package org.midonet.midolman.logging

import java.io.File
import java.nio.CharBuffer
import java.util.UUID

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterAll

import org.midonet.midolman.rules.{LiteralRule, Rule}
import org.midonet.midolman.simulation.{Chain, FileRuleLogger, PacketContext}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually
import scala.collection.JavaConversions._
import scala.util.Random

import org.midonet.logging.rule.RuleLogEventBinaryDeserializer
import org.midonet.packets.{IPAddr, IPv4Addr}

class FileRuleLoggerTest extends MidolmanSpec
                                 with BeforeAndAfterAll
                                 with MidonetEventually {

    private val logDirPath: String = "/tmp/RuleLoggerMapperTest"
    private val logDir: File = new File(logDirPath)

    private val rand = new Random

    override protected def beforeAll(): Unit = {
        super.beforeAll()
        logDir.mkdir()
    }

    override protected def afterAll(): Unit = {
        super.afterAll()
        FileUtils.deleteDirectory(logDir)
    }

    feature("FileRuleLogger") {
        scenario("Logs events to file")  {
            val (chain, rule, logger) = makeLogger()
            val ctx = makePktCtx()
            logger.logAccept(ctx, chain, rule)
            logger.flush()

            checkFile(logger.fileName, 58)

//            val deserializer =
//                new RuleLogEventBinaryDeserializer(path(logger.fileName))
//            deserializer.hasNext shouldBe true
//            val event = deserializer.next()
//            event.chainId shouldBe chain.id
        }
    }

    private def checkFile(fileName: String, size: Int): Unit = {
        val file = new File(s"$logDirPath/$fileName")
        file.exists() shouldBe true
        file.length().toInt shouldBe size
    }

    private def path(fileName: String) = s"$logDirPath/$fileName"

    private def makeLogger(logAccept: Boolean = true,
                   logDrop: Boolean = true,
                   metadata: Seq[(String, String)] = Seq())
    : (Chain, Rule, FileRuleLogger) = {
        val rule = new LiteralRule
        rule.id = UUID.randomUUID()

        val chainId = UUID.randomUUID()
        val loggerId = UUID.randomUUID()
        val logger = new FileRuleLogger(
            loggerId, s"logger-$loggerId.log",
            logAccept, logDrop,logDir.getPath)(null)

        val md = metadata.map {
            case (k, v) => (CharBuffer.wrap(k), CharBuffer.wrap(v))
        }
        val chain = new Chain(chainId, List(rule), Map[UUID, Chain](),
                              s"chain-$chainId", md, Seq(logger))

        (chain, rule, logger)
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

    def makeMetadata(entries: (String, String)*)
    : Seq[(CharBuffer, CharBuffer)] = {
        entries.map { case (k, v) => (CharBuffer.wrap(k), CharBuffer.wrap(v)) }
    }
}
