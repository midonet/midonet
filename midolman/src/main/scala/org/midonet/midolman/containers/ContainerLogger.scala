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

package org.midonet.midolman.containers

import java.io._
import java.nio.channels.FileChannel
import java.nio.charset.{Charset, CoderResult}
import java.nio.file.{DirectoryNotEmptyException, FileSystems, Files, StandardOpenOption}
import java.nio.{BufferOverflowException, ByteBuffer, CharBuffer}
import java.text.ParseException
import java.time.{ZoneId, Instant, LocalDateTime}
import java.time.format.DateTimeFormatter

import scala.collection.mutable
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.config.ContainerConfig
import org.midonet.midolman.containers.ContainerLogger._
import org.midonet.util.UnixClock

object ContainerLogger {

    case class Key(`type`: String, id: String)

    val CharsetEncoding = "UTF-8"
    val CharsetEncoder = Charset.forName(CharsetEncoding).newEncoder()
    val BufferSize = 2048
    val LogSeparators = Array('|', '\n', '\r', '\f')

}

/**
  * Implements a logger for the container service. This class writes to a log
  * file all changes made to local containers, and provides methods to parse
  * a log file to determine the containers that are currently running.
  */
class ContainerLogger(config: ContainerConfig, log: Logger) {

    private val logDirectory = {
        try {
            System.getProperty("midolman.log.dir", "/var/log/midolman")
        } catch {
            case NonFatal(e) => "/var/log/midolman"
        }
    }
    final val logPath =
        FileSystems.getDefault.getPath(s"$logDirectory/${config.logFileName}")

    private val sync = new Object
    private val clock = UnixClock()
    @volatile private var channel: FileChannel = null

    private val charBuffer = CharBuffer.allocate(BufferSize)
    private val byteBuffer = ByteBuffer.allocate(BufferSize << 1)

    /**
      * Clears the current log file if it exists. This method is synchronized.
      */
    @throws[DirectoryNotEmptyException]
    @throws[IOException]
    @throws[SecurityException]
    def clear(): Unit = sync.synchronized {
        // Close the log channel if open.
        if (channel ne null) {
            channel.close()
            channel = null
        }
        Files.deleteIfExists(logPath)
    }

    /**
      * Logs the container configuration to the log file. This method is
      * synchronized.
      */
    @throws[IllegalArgumentException]
    @throws[UnsupportedOperationException]
    @throws[IOException]
    @throws[SecurityException]
    def log(`type`: String, config: ContainerConfiguration): Unit = sync.synchronized {
        // Open the log channel if not opened.
        if (channel eq null) {
            channel = FileChannel.open(logPath,
                                       StandardOpenOption.CREATE,
                                       StandardOpenOption.WRITE)
        }
        // Get the current local date-time.
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.time),
                                               ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        try {
            // Write each log line to a common character buffer to prevent
            // additional allocations.
            charBuffer.clear()
            charBuffer.put(dateTime)
            charBuffer.put('|')
            charBuffer.put(`type`)
            charBuffer.put('|')
            charBuffer.put(config.id)
            charBuffer.put('|')
            config.flag match {
                case ContainerFlag.Created => charBuffer.put("CREATED|")
                case ContainerFlag.Deleted => charBuffer.put("DELETED|")
            }
            charBuffer.put(config.config)
            charBuffer.put('\n')
            charBuffer.flip()
            var result: CoderResult = null
            do {
                byteBuffer.clear()
                result = CharsetEncoder.encode(charBuffer, byteBuffer, false)
                byteBuffer.flip()
                channel.write(byteBuffer)
            } while (result.isOverflow)
        } catch {
            case e: BufferOverflowException =>
                log.warn("Failed to log container configuration update: " +
                         s"$config", e)
        }
    }

    /**
      * Loads the current log file, if present, and returns the list with the
      * current containers, as a map where the key is the container type and
      * name and the value is the container configuration. This method is
      * synchronized.
      */
    @throws[ParseException]
    def currentContainers(): Map[Key, String] = sync.synchronized {
        // We do not optimize this method using the file channels, and the
        // local buffers, because this is executed only once when the agent
        // starts. It can be done so in a subsequent patch.

        // If the log file does not exist, return an empty containers map.
        if (Files.notExists(logPath)) {
            return Map.empty
        }

        try {
            val map = new mutable.HashMap[Key, String]
            val reader = new BufferedReader(
                new InputStreamReader(
                    new FileInputStream(logPath.toFile), CharsetEncoding))

            try {
                var line: String = null
                var lineCount = 0
                do {
                    line = reader.readLine()
                    if (line ne null) {
                        lineCount += 1
                        try { parseLine(line, map) }
                        catch {
                            case e: ParseException =>
                                log warn s"Failed to parse log line $lineCount: " +
                                         e.getMessage
                        }
                    }
                } while (line ne null)
            } finally {
                reader.close()
            }

            map.toMap
        } catch {
            case NonFatal(e) =>
                log.warn(s"Reading the log file $logPath failed", e)
                Map.empty
        }
    }

    /**
      * Parses the current log line and updates the log entry map.
      */
    @throws[ParseException]
    private def parseLine(line: String, map: mutable.Map[Key, String]): Unit = {
        val tokens = line.split(LogSeparators)
        if (tokens.length != 5) {
            throw new ParseException(s"invalid log line '$line'", 0)
        }
        val key = Key(tokens(1), tokens(2))
        tokens(3) match {
            case "CREATED" => map.put(key, tokens(4))
            case "DELETED" => map.remove(key)
            case _ =>
                throw new ParseException(s"invalid log operation '${tokens(3)}'", 0)
        }
    }

}
