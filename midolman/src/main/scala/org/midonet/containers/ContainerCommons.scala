/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.containers

import java.io._

import scala.collection.generic.Growable
import scala.sys.process._
import scala.util.control.NonFatal

import org.midonet.midolman.logging.MidolmanLogging

/**
  * Provides common functions for service containers.
  */
trait ContainerCommons extends MidolmanLogging {

    /**
      * Writes the specified string in a text file at the given path.
      */
    @throws[IOException]
    def writeFile(contents: String, path: String): Unit = {
        val file = new File(path)
        file.getParentFile.mkdirs()
        val writer = new PrintWriter(file, "UTF-8")
        try {
            writer.print(contents)
        } finally {
            writer.close()
        }
    }

    /**
     * Executes a command and logs the output.
     */
    @throws[Exception]
    def execCmd(cmd: String): Int = {
        log.info(s"Execute: $cmd")
        val cmdLogger = ProcessLogger(line => log.debug(line),
                                      line => log.debug(line))
        cmd ! cmdLogger
    }

    /**
      * Executes a command, and appends the output to the specified list
      * arguments.
      */
    @throws[Exception]
    def execCmd(cmd: String, out: Growable[String], err: Growable[String]): Int = {
        log.info(s"Execute: $cmd")
        val cmdLogger = ProcessLogger(line => { out += line; log.debug(line) },
                                      line => { err += line; log.debug(line) })
        cmd ! cmdLogger
    }

    /**
      * Executes the first command from each pair of the following sequence. If
      * any command returns a non-zero error code, the method attempts to
      * roll-back the changes by executing the second non-null command from each
      * pair in reverse order.
      */
    @throws[Exception]
    def execCmds(commands: Seq[(String, String)]): Unit = {
        var index = 0
        try {
            while (index < commands.length) {
                if (execCmd(commands(index)._1) != 0) {
                    throw new Exception(commands(index)._1)
                }
                index += 1
            }
        } catch {
            case NonFatal(e) =>
                log warn s"Failed to execute command: ${e.getMessage} (rolling-back)"
                while (index >= 0) {
                    if (commands(index)._2 ne null) {
                        execCmd(commands(index)._2)
                    }
                    index -= 1
                }
                throw e
        }
    }
}
