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
import java.nio.charset.{CoderResult, Charset}
import java.nio.{BufferOverflowException, CharBuffer, ByteBuffer}
import java.nio.file._
import java.util
import java.util.{UUID, Collections}

import scala.util.control.NonFatal

import com.google.common.base.MoreObjects
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.config.ContainerConfig
import org.midonet.midolman.containers.ContainerLogger._

object ContainerLogger {

    case class ContainerKey(`type`: String, id: UUID, name: String) {
        override def toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add("type", `type`)
            .add("id", id)
            .add("name", name)
            .toString

    }

    val CharsetEncoding = "UTF-8"
    val CharsetEncoder = Charset.forName(CharsetEncoding).newEncoder()
    val CharsetDecoder = Charset.forName(CharsetEncoding).newDecoder()

}

/**
  * Implements a logger for the container service. This class writes to a log
  * file all changes made to local containers, and provides methods to parse
  * a log file to determine the containers that are currently running.
  */
class ContainerLogger(config: ContainerConfig, log: Logger) {

    final val logDirectory = {
        try {
            System.getProperty("midolman.log.dir", "/var/log/midolman")
        } catch {
            case NonFatal(e) => "/var/log/midolman"
        }
    }

    final val directoryPath = FileSystems.getDefault.getPath(
        s"$logDirectory/${config.logDirectory}")

    private final val sync = new Object
    private final val byteBuffer = ByteBuffer.allocate(1024)
    private final val charBuffer = CharBuffer.allocate(512)

    /**
      * Clears the current log directory.
      */
    @throws[IOException]
    @throws[SecurityException]
    def clear(): Unit = {
        if (Files.notExists(directoryPath)) {
            return
        }

        val stream = Files.newDirectoryStream(directoryPath)
        try {
            val iterator = stream.iterator()
            while (iterator.hasNext) {
                Files.deleteIfExists(iterator.next())
            }
        } finally {
            stream.close()
        }
    }

    /**
      * Logs the container configuration to the log directory. This method is
      * synchronized.
      */
    @throws[IllegalArgumentException]
    @throws[UnsupportedOperationException]
    @throws[IOException]
    @throws[SecurityException]
    def log(`type`: String, id: UUID, operation: ContainerOp): Unit = {
        val filePath = FileSystems.getDefault.getPath(
            s"$logDirectory/${config.logDirectory}/$id.${`type`}")

        if (!Files.exists(directoryPath)) {
            try {
                Files.createDirectory(directoryPath)
            } catch {
                case _: FileAlreadyExistsException => // Ignore
            }
        }

        operation.flag match {
            case ContainerFlag.Created =>
                writeFile(filePath, operation.name)
            case ContainerFlag.Deleted =>
                Files.deleteIfExists(filePath)
        }
    }

    /**
      * Loads the containers from the current log directory, and returns a list
      * with current containers. If the method fails to read the containers, it
      * returns an empty list.
      */
    def currentContainers(): util.List[ContainerKey] = {
        // If the log directory does not exist, return an empty list.
        if (Files.notExists(directoryPath)) {
            return Collections.emptyList()
        }

        try {
            val stream = Files.newDirectoryStream(directoryPath)
            try {
                val iterator = stream.iterator()
                val containers = new util.ArrayList[ContainerKey]()
                while (iterator.hasNext) {
                    val key = readContainer(iterator.next().getFileName)
                    if (key ne null) {
                        containers add key
                    }
                }
                containers
            } finally {
                stream.close()
            }
        } catch {
            case NonFatal(e) =>
                log.warn(s"Reading the log directory $directoryPath failed", e)
                Collections.emptyList()
        }
    }

    /**
      * Writes the container name to a log file.
      */
    @throws[IOException]
    private def writeFile(filePath: Path, name: String): Unit = {
        sync.synchronized {
            charBuffer.clear()
            try charBuffer.put(name)
            catch {
                case e: BufferOverflowException =>
                    throw new IOException("Container name is limited to " +
                                          s"${charBuffer.capacity()} characters",
                                          e)
            }
            charBuffer.flip()
            val channel = Files.newByteChannel(filePath,
                                               StandardOpenOption.CREATE,
                                               StandardOpenOption.WRITE)
            var result: CoderResult = null
            try {
                do {
                    byteBuffer.clear()
                    result = CharsetEncoder.encode(charBuffer, byteBuffer, false)
                    byteBuffer.flip()
                    channel.write(byteBuffer)
                } while (result.isOverflow)
            }
            finally channel.close()
        }
    }

    /**
      * Reads the container key from the specified file path. If the operation
      * fails, the method returns `null`.
      */
    private def readContainer(filePath: Path): ContainerKey = {
        val fileName = filePath.toString
        val tokens = fileName.split('.')
        if (tokens.length != 2) {
            log warn s"Invalid container log file name: '$fileName'"
            return null
        }
        try {
            ContainerKey(id = UUID.fromString(tokens(0)),
                         `type` = tokens(1),
                         name = readFile(filePath))
        } catch {
            case NonFatal(e) =>
                log.warn(s"Failed to read container log file '$fileName", e)
                null
        }
    }

    /**
      * Reads the container name from a container log file.
      */
    @throws[IOException]
    private def readFile(filePath: Path): String = {
        val path = directoryPath.resolve(filePath)
        val channel = Files.newByteChannel(path,
                                           StandardOpenOption.READ)
        sync.synchronized {
            try {
                byteBuffer.clear()
                charBuffer.clear()

                var bytesRead: Int = 0
                var result: CoderResult = null
                do {
                    bytesRead = channel.read(byteBuffer)
                    byteBuffer.flip()
                    result = CharsetDecoder.decode(byteBuffer, charBuffer, false)
                    byteBuffer.compact()
                } while (result.isUnderflow && bytesRead != -1)
                if (result.isOverflow) {
                    throw new IOException("Container name is limited to " +
                                          s"${charBuffer.capacity()} characters")
                } else {
                    charBuffer.flip()
                    charBuffer.toString
                }
            } finally {
                channel.close()
            }
        }
    }

}
