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

package org.midonet.util.logging

import java.io.{File, FileInputStream}
import java.util.zip.GZIPInputStream
import java.util.{Properties, UUID}

import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.RandomStringUtils.randomAscii
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}

import org.midonet.util.logging.TimeBasedRollingStrategy._
import org.midonet.util.{MockUnixClock, UnixClock}

@RunWith(classOf[JUnitRunner])
class RollingOutputStreamTest extends FeatureSpec
                                      with Matchers
                                      with BeforeAndAfterAll {

    val tmpDir = new File("/tmp/rolling-output-stream-test")

    var savedProperties: Properties = null

    override protected def beforeAll(): Unit = {
        tmpDir.mkdir()
        savedProperties = System.getProperties
        System.setProperty(UnixClock.USE_MOCK_CLOCK_PROPERTY, "yes")
    }

    override protected def afterAll(): Unit = {
        FileUtils.deleteDirectory(tmpDir)
        System.setProperties(savedProperties)
    }

    private def genFilePath: String = {
        s"${tmpDir.getAbsolutePath}/rollover-${UUID.randomUUID()}.log"
    }

    feature("RollingOutputStream") {
        scenario("Performs rollover when rollOver() called") {
            val header = "header"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)

            val log1Text = "First log's text."
            os.write(log1Text.getBytes)
            os.rollOver()

            val log2Text = "Second log's text."
            os.write(log2Text.getBytes)
            os.close()

            checkFile(path + ".1.gz", header, log1Text)
            checkFile(path, header, log2Text)
        }

        scenario("Performs scheduled hourly rollover") {
            val header = "[header]\n"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerHour)
            testTimeBasedRollover(header, path, os, MsPerHour)
        }

        scenario("Performs scheduled daily rollover") {
            val header = "[header]\n"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)
            testTimeBasedRollover(header, path, os, MsPerDay)
        }

        scenario("Performs size-based rollover") {
            val header = "[header]\n"
            val path = genFilePath
            val os = new SizeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, 100 + header.getBytes.length)

            val log1Part1 = randomAscii(50)
            val log1Part2 = randomAscii(50)
            os.write(log1Part1.getBytes)
            os.write(log1Part2.getBytes)

            val log2 = randomAscii(50)
            os.write(log2.getBytes)

            val log3Part1 = randomAscii(51)
            val log3Part2 = randomAscii(49)
            os.write(log3Part1.getBytes)
            os.write(log3Part2.getBytes)
            os.close()

            checkFile(path + ".2.gz", header, log1Part1 + log1Part2)
            checkFile(path + ".1.gz", header, log2)
            checkFile(path, header, log3Part1 + log3Part2)
        }

        scenario("Throws exceptions when using closed stream.") {
            val header = "[Header]\n"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)
            os.close()

            an [IllegalStateException] should be thrownBy
                os.write("Blah".getBytes)
            an [IllegalStateException] should be thrownBy
                os.write("Blah".getBytes, 0, 4)
            an [IllegalStateException] should be thrownBy
                os.write('a')

            an [IllegalStateException] should be thrownBy os.flush()
            an [IllegalStateException] should be thrownBy os.close()
        }

        scenario("Forces rollover on creation when file already exists.") {
            val header = "[Header]\n"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)
            val log1Text = "Log 1 text"
            os.write(log1Text.getBytes)
            os.close()

            checkFile(path, header, log1Text, delete = false)
            new File(path + ".1.gz").exists shouldBe false

            // Creation of new stream should force rollover.
            val os2 = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)
            checkFile(path + ".1.gz", header, log1Text, delete = false)
            checkFile(path, header, "", delete = false)

            val log2Text = "Log 2 text"
            os2.write(log2Text.getBytes)
            os2.close()

            checkFile(path + ".1.gz", header, log1Text, delete = false)

            // Delete current log. Then creating a new stream should not force
            // a rollover.
            checkFile(path, header, log2Text, delete = true)

            val os3 = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)
            checkFile(path + ".1.gz", header, log1Text)
            checkFile(path, header, "")
            new File(path + ".2.gz").exists shouldBe false
        }

        scenario("Supports flush()") {
            val header = "[Header]\n"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)
            val body = "Body text"
            os.write(body.getBytes)

            // Not flushed yet, should only have header.
            checkFile(path, header, "", delete = false)

            // Flush and try again.
            os.flush()
            checkFile(path, header, body, delete = false)

            os.close()
            checkFile(path, header, body)
        }

        scenario("Supports other write() overloads") {
            val header = "[Header]\n"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)
            os.write('a'.toByte)
            os.write('b'.toByte)
            os.write('c'.toByte)
            advanceToNextInterval(os.clock, MsPerDay)
            Thread.sleep(200)

            os.write('d'.toByte)
            os.write("abcdefghi".getBytes, 4, 2)
            advanceToNextInterval(os.clock, MsPerDay)
            Thread.sleep(200)

            os.write("abcdefghi".getBytes, 6, 3)
            os.close()

            checkFile(path + ".2.gz", header, "abc")
            checkFile(path + ".1.gz", header, "def")
            checkFile(path, header, "ghi")
        }

        scenario("Honors maxFiles limit") {
            val header = "[Header]\n"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, true, header.getBytes, MsPerDay)

            val logs = Seq("First log text", "Second log text",
                           "Third log text", "Fourth log text",
                           "Fifth log text", "Sixth log text",
                           "Seventh log text")

            for (log <- logs.dropRight(1)) {
                os.write(log.getBytes)
                os.rollOver()
            }

            os.write(logs(6).getBytes)
            os.close()

            checkFile(path + ".5.gz", header, logs(1))
            checkFile(path + ".4.gz", header, logs(2))
            checkFile(path + ".3.gz", header, logs(3))
            checkFile(path + ".2.gz", header, logs(4))
            checkFile(path + ".1.gz", header, logs(5))
            checkFile(path, header, logs(6))

            // Should not exist due to five-file max.
            new File(path + ".6.gz").exists shouldBe false
        }

        scenario("Does not compress old files unless specified") {
            val header = "[Header]\n"
            val path = genFilePath
            val os = new TimeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, compressOldFiles = false, header.getBytes, MsPerDay)

            val logs = Seq("First log text", "Second log text")
            os.write(logs(0).getBytes)
            os.rollOver()

            os.write(logs(1).getBytes)
            os.close()

            checkFile(path + ".1", header, logs(0))
            checkFile(path, header, logs(1))
        }
    }

    private def testTimeBasedRollover(
            header: String, path: String,
            os: TimeBasedRollingStaticHeaderBufferedOutputStream,
            interval: Int): Unit = {
        val log1Text = "First log's text"
        os.write(log1Text.getBytes)

        // Trigger rollover
        advanceToNextInterval(os.clock, interval)
        val log2TextLine1 = "Second log text, first line\n"
        os.write(log2TextLine1.getBytes)

        // Advance time, but not enough to trigger rollover.
        os.clock.asInstanceOf[MockUnixClock].time += (interval / 2)
        val log2TextLine2 = "Second log text, second line"
        os.write(log2TextLine2.getBytes)

        // Trigger another rollover.
        advanceToNextInterval(os.clock, interval)
        val log3Text = "Third log text"
        os.write(log3Text.getBytes)
        os.close()

        checkFile(path + ".2.gz", header, log1Text)
        checkFile(path + ".1.gz", header, log2TextLine1 + log2TextLine2)
        checkFile(path, header, log3Text)
    }

    private def advanceToNextInterval(clock: UnixClock, interval: Int): Unit = {
        val mockClock = clock.asInstanceOf[MockUnixClock]
        mockClock.time = (((mockClock.time + 1) / interval) + 1) * interval
    }

    private def checkFile(path: String, header: String, body: String,
                          delete: Boolean = true): Unit = {
        val file = new File(path)
        file.exists shouldBe true

        val in = if (path.endsWith(".gz")) {
            new GZIPInputStream(new FileInputStream(path))
        } else {
            new FileInputStream(path)
        }

        // Quintuple the buffer size just to make sure there's enough to read
        // the whole uncompressed file.
        val buf = new Array[Byte](file.length.toInt * 5)

        // Check header.
        val headerLen = header.getBytes.length
        in.read(buf, 0, headerLen)
        new String(buf.take(headerLen)) shouldBe header

        // Check body.
        val bodyLen = in.read(buf)
        new String(buf.take(bodyLen)) shouldBe body

        if (delete)
            file.delete()
    }
}
