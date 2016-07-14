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

package org.midonet.logging.rule

import java.io.{FileNotFoundException, PrintStream}

import org.rogach.scallop.ScallopConf
import org.rogach.scallop.exceptions.ScallopException

object BinaryLogTranslator extends App {

    private val opts = new ScallopConf(args) {
        banner("""Converts binary Midonet security logs into text.
                 |
                 |Usage: mm-logxl [-o OUTFILE] INFILE
               """.stripMargin)

        val inFile = trailArg[String](
            "in-file", "Binary log file to read from.")
        val outFile = opt[String](
            "out-file", 'o',
            "File to write translated logs to. Defaults to console.")

        // Print help message on invalid arguments.
        override def onError(e: Throwable) = e match {
            case ScallopException(message) =>
                println(message)
                printHelp
            case ex => super.onError(ex)
        }
    }

    val deserializer = try {
        new RuleLogEventBinaryDeserializer(opts.inFile())
    } catch {
        case ex: FileNotFoundException =>
            System.err.println("File not found: " + ex.getMessage)
            System.exit(1)
            null // Satisfy type checker.
        case ex: IllegalArgumentException =>
            System.err.println(ex.getMessage)
            System.exit(1)
            null
    }

    val fw = opts.outFile.get match {
        case Some(path) =>
            try {
                new PrintStream(opts.outFile())
            } catch {
                case ex: FileNotFoundException =>
                    System.err.println("File not found: " + ex.getMessage)
                    System.exit(1)
                    null // Satisfy type checker.
            }
        case None => System.out // Default to console.
    }

    while (deserializer.hasNext) {
        val e = try {
            deserializer.next()
        } catch {
            case ex: IllegalArgumentException =>
                System.err.println(ex.getMessage)
                System.exit(1)
                null // Satisfy type checker
        }

        val md = e.metadata.map { case (k, v) => s"$k=$v" }.mkString(", ")

        fw.println(s"TIME=${e.time} SRC=${e.srcIp} DST=${e.dstIp} " +
                   s"SPT=${e.srcPort} DPT=${e.dstPort} PROTO=${e.nwProto} " +
                   s"CHAIN=${e.chainId} RULE=${e.chainId} MD=[$md] ${e.result}")
        fw.flush()
    }
}
