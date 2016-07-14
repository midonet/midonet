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

package org.midonet.logging.rule

import java.io._
import java.util.UUID
import java.util.zip.GZIPInputStream

import scala.util.control.NonFatal

import org.rogach.scallop.ScallopConf
import org.rogach.scallop.exceptions.ScallopException

object BinaryLogTranslator extends App {

    private val opts = new ScallopConf(args) {
        banner("""Converts binary Midonet security logs into text.
                 |
                 |Usage: mm-logxl [-o OUTFILE] [-z] INFILE
               """.stripMargin)

        val inFile = trailArg[String](
            "in-file", "Binary log file to read from.")
        val outFile = opt[String](
            "out-file", 'o',
            "File to write translated logs to. Defaults to console.")
        val zipped = opt[Boolean](
            "zipped", 'z', "Binary log file is gzipped", default = Some(false))

        // Print help message on invalid arguments.
        override def onError(e: Throwable) = e match {
            case ScallopException(message) =>
                println(message)
                printHelp
                sys.exit(0)
            case ex => super.onError(ex)
        }
    }

    val inFile = if (opts.zipped()) {
        try unzipFile(opts.inFile()) catch {
            case NonFatal(t) =>
                System.err.println("Could not unzip file " + opts.inFile() +
                                   ": " + t.getMessage)
                System.exit(1)
                null
        }
    } else {
        opts.inFile()
    }

    val deserializer = try {
        new RuleLogEventBinaryDeserializer(inFile)
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
        case Some(outPath) =>
            try {
                new PrintStream(outPath)
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

        fw.println(s"LOGGER=${e.loggerId} TIME=${e.time} SRC=${e.srcIp} " +
                   s"DST=${e.dstIp} SPT=${e.srcPort} DPT=${e.dstPort} " +
                   s"PROTO=${e.nwProto} CHAIN=${e.chainId} RULE=${e.chainId} " +
                   s"MD=[$md] ${e.result}")
        fw.flush()
    }

    private def unzipFile(inPath: String): String = {
        // There doesn't seem to be any way to get SBE to read from a
        // GZIPInputStream, so we need to unzip to a temp file.
        val tmpDir = System.getProperty("java.io.tmpdir")
        val outFile = new File(s"$tmpDir/mm-logxl-${UUID.randomUUID()}.rlg")
        outFile.deleteOnExit()

        val in = new GZIPInputStream(new FileInputStream(inPath))
        val out = new FileOutputStream(outFile)

        val unzipBuf = new Array[Byte](8192)
        while (in.available() > 0) {
            val bytesRead = in.read(unzipBuf)
            // It seems that GZIPInputStream.available() doesn't return 0 until
            // you try to read once more after reading all the available data,
            // so on the last iteration through this loop, bytesRead will be -1.
            if (bytesRead > 0)
                out.write(unzipBuf, 0, bytesRead)
        }

        in.close()
        out.close()
        outFile.getAbsolutePath
    }
}
