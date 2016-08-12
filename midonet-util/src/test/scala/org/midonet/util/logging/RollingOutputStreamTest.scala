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
import java.util.UUID
import java.util.zip.GZIPInputStream

import org.apache.commons.io.FileUtils

import DailyRollingStrategy.msPerDay
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}

class RollingOutputStreamTest extends FeatureSpec
                                      with Matchers
                                      with BeforeAndAfterAll {

    val tmpDir = new File("/tmp/rolling-output-stream-test")

    override protected def beforeAll(): Unit = {
        tmpDir.mkdir()
    }

    override protected def afterAll(): Unit = {
        FileUtils.deleteDirectory(tmpDir)
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
            os.write(log1Text.getBytes())
            os.rollOver()

            val log2Text = "Second log's text."
            os.write(log2Text.getBytes())
            os.close()

            checkFile(path + ".1.gz", header, log1Text)
            checkFile(path, header, log2Text)
        }

        scenario("Performs scheduled rollover") {
            val header = "[header]\n"
            val path = genFilePath
            val os = new DailyRollingStaticHeaderBufferedOutputStream(
                path, 5, header.getBytes)

            val log1Text = "First log's text"
            os.write(log1Text.getBytes)

            var now = System.currentTimeMillis()
            var oldRolloverTime = os.scheduleNextRollover(now + 100)
            oldRolloverTime shouldBe (now / msPerDay + 1) * msPerDay

            // Wait for rollover
            Thread.sleep(200)
            val log2Text = "Second log text"
            os.write(log2Text.getBytes)

            now = System.currentTimeMillis()
            oldRolloverTime = os.scheduleNextRollover(now + 100)
            oldRolloverTime shouldBe (now / msPerDay + 1) * msPerDay

            // Wait for rollover.
            Thread.sleep(200)
            val log3Text = "Third log text"
            os.write(log3Text.getBytes)
            os.close()

            checkFile(path + ".2.gz", header, log1Text)
            checkFile(path + ".1.gz", header, log2Text)
            checkFile(path, header, log3Text)
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
            os.scheduleNextRollover(System.currentTimeMillis() + 100)
            Thread.sleep(200)

            os.write('d'.toByte)
            os.write("abcdefghi".getBytes, 4, 2)
            os.scheduleNextRollover(System.currentTimeMillis() + 100)
            Thread.sleep(200)

            os.write("abcdefghi".getBytes, 6, 3)
            os.close()

            checkFile(path + ".2.gz", header, "abc")
            checkFile(path + ".1.gz", header, "def")
            checkFile(path, header, "ghi")
        }
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

        val buf = new Array[Byte](file.length.toInt)

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
