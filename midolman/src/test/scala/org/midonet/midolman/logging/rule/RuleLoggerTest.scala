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

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.packets.IPv6Addr

@RunWith(classOf[JUnitRunner])
class RuleLoggerTest extends RuleLoggerTestBase {

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
    }
}
