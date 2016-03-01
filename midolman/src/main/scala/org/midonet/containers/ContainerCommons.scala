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
      * Executes the specified command in a child process and calls the
      * specified function for every line of data received from the child
      * process' input or error streams.
      * @return The process exit code.
      */
    @throws[Exception]
    def execute(command: String, processLine: String => Unit): Int = {
        log debug s"Execute: $command"
        val args = command.split("""\s+""")
        val builder = new ProcessBuilder(args: _*).redirectErrorStream(true)
        val process = builder.start()
        val reader = new BufferedReader(new InputStreamReader(process.getInputStream))

        try {
            var line: String = null
            do {
                line =
                    try reader.readLine()
                    catch {
                        case e: IOException => null
                    }
                if (line ne null) {
                    processLine(line)
                }
            } while (line ne null)
            process.waitFor()
        } finally {
            try reader.close()
            catch {
                case e: IOException =>
                    // We know of no reason to get an IOException, but if
                    // we do, there is nothing else to do but carry on.
                    log.debug("Failed to close external process reader", e)
            }
        }
    }

    /**
     * Executes a command and logs the output at the DEBUG logging level.
     */
    @throws[Exception]
    def execute(command: String): Int = {
        execute(command, line => log.debug(line))
    }

    /**
      * Executes a command, and logs and returns the output.
      */
    @throws[Exception]
    def executeWithOutput(command: String): (Int, String) = {
        val builder = new StringBuilder
        val code = execute(command, line => {
            builder append line
            builder append '\n'
        })
        (code, builder.toString)
    }

    /**
      * Executes the first command from each pair of the following sequence. If
      * any command returns a non-zero error code, the method attempts to
      * roll-back the changes by executing the second non-null command from each
      * pair in reverse order.
      */
    @throws[Exception]
    def executeCommands(commands: Seq[(String, String)]): Unit = {
        var index = 0
        try {
            while (index < commands.length) {
                if (execute(commands(index)._1) != 0) {
                    throw new Exception(commands(index)._1)
                }
                index += 1
            }
        } catch {
            case NonFatal(e) =>
                log warn s"Failed to execute command: ${e.getMessage} (rolling-back)"
                while (index >= 0) {
                    if (commands(index)._2 ne null) {
                        execute(commands(index)._2)
                    }
                    index -= 1
                }
                throw e
        }
    }
}
