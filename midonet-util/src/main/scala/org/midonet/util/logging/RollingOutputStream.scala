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
      */
    protected def shouldRollOver: Boolean

    /**
      * Invoked on the first write and after each rollover to schedule the next
      * rollover. Implementation is optional, but may be helpful to simplify
      * shouldRollover implementation.
      */
    protected def scheduleNextRollover(): Unit = {}
}

private object DailyRollingStrategy {
    val msPerDay = 24 * 60 * 60 * 1000
}

/**
  * Implementation of RollingStrategy that rotates logs once per day, at
  * midnight.
  */
trait DailyRollingStrategy extends RollingStrategy {
    import DailyRollingStrategy._

    protected var nextRollover = 0L

    override protected def shouldRollOver: Boolean = {
        if (nextRollover == 0L) {
            scheduleNextRollover()
            false
        } else {
            System.currentTimeMillis() >= nextRollover
        }
    }

    override protected def scheduleNextRollover(): Unit = {
        val now = System.currentTimeMillis()
        nextRollover = ((now / msPerDay) + 1) * msPerDay
    }

    /**
      * For testing rollover. Schedules next rollover for specified time and
      * returns the previously-scheduled rollover time. */
    private[logging] def scheduleNextRollover(time: Long): Long = {
        val oldNextRollover = nextRollover
        nextRollover = time
        oldNextRollover
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

    /** Write the header to file. */
    protected def writeHeader(): Unit

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

    override protected def writeHeader(): Unit = {
        out.write(header)
        out.flush()
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
  *
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

    protected var out: OutputStream = createOutputStream(path)
    if (!hasHeader(destFile)) {
        writeHeader()
    }

    override def write(b: Array[Byte]): Unit = {
        checkNotClosed()
        if (shouldRollOver)
            rollOver()
        out.write(b)
    }

    override def write(b: Int): Unit = {
        checkNotClosed()
        if (shouldRollOver)
            rollOver()
        out.write(b)
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        checkNotClosed()
        if (shouldRollOver)
            rollOver()
        out.write(b, off, len)
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
        writeHeader()
    }

    private def checkNotClosed(): Unit = {
        if (out == null)
            throw new IllegalStateException("Stream is closed.")
    }
}

class DailyRollingStaticHeaderBufferedOutputStream(
        path: String, maxFiles: Int,
        protected val header: Array[Byte])
    extends RollingOutputStream(path, maxFiles)
            with BufferedOutputStreamFactory
            with DailyRollingStrategy
            with StaticHeaderManager