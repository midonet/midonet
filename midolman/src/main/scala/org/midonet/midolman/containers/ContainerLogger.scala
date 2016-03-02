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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.util
import java.util.{Collections, UUID}

import scala.util.control.NonFatal

import com.google.common.base.MoreObjects
import com.typesafe.scalalogging.Logger

import org.midonet.containers.models.Containers
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

    val CharsetEncoder = StandardCharsets.UTF_8.newEncoder()
    val CharsetDecoder = StandardCharsets.UTF_8.newDecoder()

    final val LogDirectory = {
        try {
            System.getProperty("midolman.log.dir", "/var/log/midolman")
        } catch {
            case NonFatal(e) => "/var/log/midolman"
        }
    }

}

/**
  * Implements a logger for the container service. This class writes to a log
  * file all changes made to local containers, and provides methods to parse
  * a log file to determine the containers that are currently running.
  */
class ContainerLogger(config: ContainerConfig, log: Logger) {

    final val directoryPath = FileSystems.getDefault.getPath(
        s"$LogDirectory/${config.logDirectory}")

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
            s"$LogDirectory/${config.logDirectory}/$id")

        if (!Files.exists(directoryPath)) {
            try {
                Files.createDirectory(directoryPath)
            } catch {
                case _: FileAlreadyExistsException => // Ignore
            }
        }

        operation.flag match {
            case ContainerFlag.Created =>
                writeFile(filePath, `type`, id, operation.name)
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
    private def writeFile(filePath: Path, `type`: String, id: UUID,
                          name: String): Unit = {
        val channel = Files.newByteChannel(filePath,
                                           StandardOpenOption.CREATE,
                                           StandardOpenOption.WRITE)
        try {
            val log = Containers.Log.newBuilder()
                .setType(`type`)
                .setId(id.toString)
                .setName(name)
                .build()
            channel.write(ByteBuffer.wrap(log.toByteArray))
        } finally {
            channel.close()
        }
    }

    /**
      * Reads the container key from the specified file path. If the operation
      * fails, the method returns `null`.
      */
    private def readContainer(filePath: Path): ContainerKey = {
        val fileName = filePath.toString
        try {
            readFile(filePath)
        } catch {
            case NonFatal(e) =>
                log.warn(s"Failed to read container log file '$fileName", e)
                null
        }
    }

    /**
      * Reads the container name from a container log file.
      */
    @throws[Exception]
    private def readFile(filePath: Path): ContainerKey = {
        val path = directoryPath.resolve(filePath)
        val inputStream = new FileInputStream(path.toFile)
        try {
            val log = Containers.Log.newBuilder().mergeFrom(inputStream).build()
            ContainerKey(`type` = log.getType,
                         id = UUID.fromString(log.getId),
                         name = log.getName)
        } finally {
            inputStream.close()
        }
    }

}
