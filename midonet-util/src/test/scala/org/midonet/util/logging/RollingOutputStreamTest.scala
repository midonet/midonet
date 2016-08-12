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

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.{Properties, UUID}
import java.util.zip.GZIPInputStream

import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}

import org.midonet.util.logging.TimeBasedRollingStrategy._
import org.midonet.util.{MockUnixClock, UnixClock}

import org.apache.commons.lang3.RandomStringUtils.randomAscii

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
            val os = new DailyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)

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
            val os = new HourlyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)
            testTimeBasedRollover(header, path, os, msPerHour)
        }

        scenario("Performs scheduled daily rollover") {
            val header = "[header]\n"
            val path = genFilePath
            val os = new DailyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)
            testTimeBasedRollover(header, path, os, msPerDay)
        }

        scenario("Performs size-based rollover") {
            val header = "[header]\n"
            val path = genFilePath
            val os = new SizeBasedRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes, 100 + header.getBytes.length)

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

        scenario("Throws exception on corrupt header") {
            val header = "Header\n"
            val path = genFilePath

            val fos = new FileOutputStream(path)
            fos.write("Blah blah blah".getBytes)
            fos.close()

            an [IllegalArgumentException] should be thrownBy
                new DailyRollingStaticHeaderBufferedOutputStream(
                    path, 5, header.getBytes)
            val f = new File(path)
            f.delete()
        }

        scenario("Throws exception on too-short header.") {
            val header = "Header\n"
            val path = genFilePath

            val fos = new FileOutputStream(path)
            fos.write("Header".getBytes) // No \n
            fos.close()

            an [IllegalArgumentException] should be thrownBy
                new DailyRollingStaticHeaderBufferedOutputStream(
                    path, 5, header.getBytes)
            val f = new File(path)
            f.delete()
        }

        scenario("Throws exceptions when using closed stream.") {
            val header = "[Header\n]"
            val path = genFilePath
            val os = new DailyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)
            os.close()

            an [IllegalStateException] should be thrownBy
                os.write("Blah".getBytes)

            an [IllegalStateException] should be thrownBy os.flush()
            an [IllegalStateException] should be thrownBy os.close()
            an [IllegalStateException] should be thrownBy os.rollOver()
        }

        scenario("Resumes writing to file with valid header") {
            val header = "[Header\n]"
            val path = genFilePath
            val os = new DailyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)
            val line1 = "Body line 1\n"
            os.write(line1.getBytes)
            os.close()

            val os2 = new DailyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)
            val line2 = "Body line 2\n"
            os2.write(line2.getBytes)
            os2.rollOver()

            val line3 = "Body line 3\n"
            os2.write(line3.getBytes)
            os2.close()

            checkFile(path + ".1.gz", header, line1 + line2)
            checkFile(path, header, line3)
        }

        scenario("Supports flush()") {
            val header = "[Header\n]"
            val path = genFilePath
            val os = new DailyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)
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
            val header = "[Header\n]"
            val path = genFilePath
            val os = new DailyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)
            os.write('a'.toByte)
            os.write('b'.toByte)
            os.write('c'.toByte)
            advanceToNextInterval(os.clock, msPerDay)
            Thread.sleep(200)

            os.write('d'.toByte)
            os.write("abcdefghi".getBytes, 4, 2)
            advanceToNextInterval(os.clock, msPerDay)
            Thread.sleep(200)

            os.write("abcdefghi".getBytes, 6, 3)
            os.close()

            checkFile(path + ".2.gz", header, "abc")
            checkFile(path + ".1.gz", header, "def")
            checkFile(path, header, "ghi")
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
