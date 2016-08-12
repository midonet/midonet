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

import java.io._
import java.util.UUID

import scala.sys.process._

import org.slf4j.LoggerFactory

import org.midonet.util.UnixClock

/** Creates underlying OutputStream for RollingOutputStream. */
trait OutputStreamFactory {
    protected def createOutputStream(path: String): OutputStream
}

/**
  * Creates BufferedOutputStream as underlying OutputStream for
  * RollingOutputStream.
  */
trait BufferedOutputStreamFactory extends OutputStreamFactory {
    override protected def createOutputStream(path: String): OutputStream = {
        new BufferedOutputStream(new FileOutputStream(path, true))
    }
}

/**
  * Rolling strategy for RollingOutputStream.
  */
trait RollingStrategy {

    /**
      * Invoked on each write to determine whether the logs should be rotated
      * before writing, so should be fast and allocation-free.
      *
      * @param len length of next write, in bytes
      */
    protected def shouldRollOver(len: Int): Boolean

    /**
      * Invoked on the first write and after each rollover to schedule the next
      * rollover. Implementation is optional, but may be helpful to simplify
      * shouldRollover implementation.
      */
    protected def scheduleNextRollover(): Unit = {}
}

private object TimeBasedRollingStrategy {
    val msPerHour = 60 * 60 * 1000
    val msPerDay = 24 * msPerHour
}

/**
  * Implementation of RollingStrategy that rotates logs at fixed intervals.
  */
trait TimeBasedRollingStrategy extends RollingStrategy {
    // Length of rolling interval, in milliseconds
    protected val rollingInterval: Long

    // Mockable system clock.
    protected[logging] val clock = UnixClock()

    // Unix time of next scheduled rollover.
    protected var nextRollover = 0L

    override protected def shouldRollOver(len: Int): Boolean = {
        if (nextRollover == 0L) {
            scheduleNextRollover()
            false
        } else {
            clock.time >= nextRollover
        }
    }

    override protected def scheduleNextRollover(): Unit = {
        nextRollover = ((clock.time / rollingInterval) + 1) * rollingInterval
    }
}

/**
  * Implementation of RollingStrategy that rotates logs when they reach a
  * specified size.
  *
  * If len > maxFileSize - header size, then this strategy will result in
  * a rollover even if the file contains nothing but the header. Since there's
  * no obvious reason why anyone would want to do this, this doesn't seem worth
  * fixing.
  */
trait SizeBasedRollingStrategy extends RollingStrategy {

    protected val maxFileSize: Long
    protected var fileSize: Long

    override protected def shouldRollOver(len: Int): Boolean = {
        fileSize + len > maxFileSize
    }
}

/**
  * Used by RollingOutputStream to check headers on existing files and write
  * headers as needed.
  */
trait HeaderManager {
    /** Output stream for writing the header. Provided by RollingOutputStream */
    protected var out: OutputStream

    /**
      * Returns true if file has a valid header and false if it's empty or
      * doesn't exist. ThrowIllegalArgumentException if file is non-empty but
      * does not have a valid header.
      */
    protected def hasHeader(file: File): Boolean

    /**
      * Write the header to out.
      *
      * @return Number of bytes written.
      */
    protected def writeHeader(): Int

    protected def throwInvalidHeaderError(file: File): Unit = {
        throw new IllegalArgumentException(
            s"File ${file.getAbsolutePath} exists and is not empty, but " +
            "does not have a valid header.")
    }
}

/** HeaderManager that writes a header consisting of a fixed array of bytes. */
trait StaticHeaderManager extends HeaderManager {
    // Provided by RollingOutputStream implementation.
    protected val header: Array[Byte]

    override protected def hasHeader(file: File): Boolean = {
        if (!file.exists() || file.length() == 0) {
            false
        } else if (file.length() < header.length) {
            throwInvalidHeaderError(file)
            false // Unreachable, but the type checker doesn't know that.
        } else {
            val buf = new Array[Byte](header.length)
            val in = new FileInputStream(file)
            in.read(buf)
            if (!buf.sameElements(header)) {
                throwInvalidHeaderError(file)
            }
            true
        }
    }

    override protected def writeHeader(): Int = {
        out.write(header)
        out.flush()
        header.length
    }
}

/**
  * OutputStream that periodically rolls over and compresses the destination
  * file according to the specified RollingStrategy. A concrete implementation
  * of RollingOutputStream must mix in concrete implementations of the
  * following three abstract traits:
  *
  * OutputStreamFactory: Creates the underlying OutputStream.
  *
  * RollingStrategy: Determines when the destination file will be rolled over.
  *
  * HeaderManager: Handles writing headers to newly-created files if needed.
  *
  * A concrete implementation of RollingOutputStream must mix in concrete
  * implementations of these traits or implement their abstract methods
  * directly.
  *
  * Requires the external Linux utility 'logrotate'.
  *
  * @param path The live version of the destination file, e.g.
  *             /var/log/example.log. Older versions will be rotated to
  *             /var/log/example.log.1.gz, /var/log/example.log.2.gz, etc.
  * @param maxFiles Maximum number of rotated files to keep. If there are
  *                 already maxFiles files retained and the RollingStrategy
  *                 calls for a rollover, the oldest file will be deleted.
  */
abstract class RollingOutputStream(val path: String, val maxFiles: Int)
    extends OutputStream {
    this: OutputStreamFactory with RollingStrategy with HeaderManager =>

    protected val log = LoggerFactory.getLogger(getClass)

    private val tmpDir = System.getProperty("java.io.tmpdir")

    // logrotate conf and status files. Required to run logrotate.
    private val confFile = new File(s"$tmpDir/${UUID.randomUUID()}.conf")
    private val statusFile = new File(confFile.getAbsolutePath + ".status")

    private val destFile = new File(path)

    // Current size of dest file.
    protected var fileSize: Long = 0L

    protected var out: OutputStream = createOutputStream(path)

    fileSize = if (!hasHeader(destFile)) {
        writeHeader()
    } else {
        destFile.length
    }


    override def write(b: Array[Byte]): Unit = {
        checkNotClosed()
        if (shouldRollOver(b.length))
            rollOver()
        out.write(b)
        fileSize += b.length
    }

    override def write(b: Int): Unit = {
        checkNotClosed()
        if (shouldRollOver(1))
            rollOver()
        out.write(b)
        fileSize += 1
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        checkNotClosed()
        if (shouldRollOver(len))
            rollOver()
        out.write(b, off, len)
        fileSize += len
    }

    override def flush(): Unit = {
        checkNotClosed()
        out.flush()
    }

    override def close(): Unit = {
        checkNotClosed()
        out.close()
        out = null
    }

    def rollOver(): Unit = {
        checkNotClosed()

        // Create the logrotate config file.
        val confFileOs = new FileOutputStream(confFile, false)
        confFileOs.write(
            s"""
               |$path {
               |    rotate $maxFiles
               |    compress
               |}
            """.stripMargin.getBytes)
        confFileOs.close()

        // Close stream before rotating.
        out.close()

        // Run logrotate, capturing stderr in case it fails.
        val errBldr = new StringBuilder
        val plog = ProcessLogger(o => Unit, e => errBldr.append(e).append('\n'))
        val p = (s"logrotate -f ${confFile.getAbsolutePath} " +
                s"--state $statusFile").run(plog)
        if (p.exitValue() != 0) {
            log.error("Log rotate failed with error code {}: {}",
                      Array(p.exitValue().toString,
                            errBldr.toString()))
        }

        confFile.delete()
        statusFile.delete()

        scheduleNextRollover()

        // Reopen output stream with new file.
        out = createOutputStream(path)
        fileSize = writeHeader()
    }

    private def checkNotClosed(): Unit = {
        if (out == null)
            throw new IllegalStateException("Stream is closed.")
    }
}

class TimeBasedRollingStaticHeaderBufferedOutputStream(
        path: String, maxFiles: Int,
        protected val header: Array[Byte],
        protected val rollingInterval: Long)
    extends RollingOutputStream(path, maxFiles)
            with BufferedOutputStreamFactory
            with TimeBasedRollingStrategy
            with StaticHeaderManager

class DailyRollingStaticHeaderBufferedOutputStream(
        path: String, maxFiles: Int, header: Array[Byte])
    extends TimeBasedRollingStaticHeaderBufferedOutputStream(
        path, maxFiles, header, TimeBasedRollingStrategy.msPerDay)

class HourlyRollingStaticHeaderBufferedOutputStream(
        path: String, maxFiles: Int, header: Array[Byte])
    extends TimeBasedRollingStaticHeaderBufferedOutputStream(
        path, maxFiles, header, TimeBasedRollingStrategy.msPerHour)

class SizeBasedRollingStaticHeaderBufferedOutputStream(
        path: String, maxFiles: Int,
        protected val header: Array[Byte],
        protected val maxFileSize: Long)
    extends RollingOutputStream(path, maxFiles)
        with BufferedOutputStreamFactory
        with SizeBasedRollingStrategy
        with StaticHeaderManager