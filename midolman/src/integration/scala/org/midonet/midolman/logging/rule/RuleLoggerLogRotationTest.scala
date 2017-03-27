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

import java.nio.file.Files

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.util.logging.RollingOutputStream

@RunWith(classOf[JUnitRunner])
class RuleLoggerLogRotationTest extends RuleLoggerTestBase {

    feature("FileRuleLogger") {
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

            def logEvents(numEvents: Int, flush: Boolean = true) =
                this.logEvents(numEvents, logger, chain, rule, flush)

            logEvents(1)

            // Close the output stream to trigger an exception on the next write
            val os = getFieldValue[RollingOutputStream](eventHandler, "os")
            os.close()

            checkFile(headerSize + recordSize)

            // Should cause an exception, and not log the event.
            // Don't flush, because the call to flush() is outside the event
            // handler loop and raises an exception that won't get caught.
            logEvents(1, flush = false)
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
}
