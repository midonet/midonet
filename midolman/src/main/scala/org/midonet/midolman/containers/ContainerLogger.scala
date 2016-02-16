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
import java.nio.file._
import java.util
import java.util.Collections

import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.config.ContainerConfig
import org.midonet.midolman.containers.ContainerLogger._

object ContainerLogger {

    case class ContainerKey(name: String, `type`: String)

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
    def log(`type`: String, operation: ContainerOp): Unit = {
        val filePath = FileSystems.getDefault.getPath(
            s"$logDirectory/${config.logDirectory}/${operation.name}.${`type`}")

        if (!Files.exists(directoryPath)) {
            try {
                Files.createDirectory(directoryPath)
            } catch {
                case _: FileAlreadyExistsException => // Ignore
            }
        }

        operation.flag match {
            case ContainerFlag.Created =>
                Files.newByteChannel(filePath,
                                     StandardOpenOption.CREATE,
                                     StandardOpenOption.WRITE).close()
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
                    val fileName = iterator.next().getFileName.toString
                    val tokens = fileName.split('.')
                    if (tokens.length == 2) {
                        containers add ContainerKey(name = tokens(0),
                                                    `type` = tokens(1))
                    } else {
                        log warn s"Invalid container log file name: '$fileName'"
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

}
