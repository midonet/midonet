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
package org.midonet.midolman

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeoutException

import com.typesafe.scalalogging.Logger
import org.midonet.util.concurrent.CallingThreadExecutionContext
import org.slf4j.LoggerFactory
import scala.concurrent.Promise

class WatchedProcess {
    val log = Logger(LoggerFactory.getLogger("org.midonet.midolman.watchdog"))

    @volatile
    private var running = false
    private var intervalMillis: Int = 0
    private var pipePath: Path = null
    private var pipe: FileChannel = null
    private var timer: Timer = null

    private val shutdownHook = new Thread() {
        override def run() {
            log.info("Stopping watchdog thread")
            timer.cancel()
            running = false
        }
    }

    private def initializationTimeout(promise: Promise[_]) = {
        new TimerTask() {
            override def run() {
                promise.tryFailure(new TimeoutException("Failed to initialize the daemon"))
            }
        }
    }

    private val tick = new TimerTask() {
        val buf = ByteBuffer.allocate(1);
        buf.put(57.toByte)
        buf.flip()

        override def run() {
            try {
                if (running) {
                    buf.position(0)
                    pipe.write(buf)
                }
            } catch {
                case e: IOException => log.warn("Could not write to watchdog pipe", e)
            }
        }
    }

    private def readConfig(): Boolean = {
        val pipeStr = System.getenv("WDOG_PIPE")
        if (pipeStr eq null) {
            log.info("Disabling watchdog: WDOG_PIPE environment var is not set")
            return false
        }
        val timeoutStr = System.getenv("WDOG_TIMEOUT")
        if (timeoutStr eq null) {
            log.warn("Disabling watchdog: WDOG_TIMEOUT environment var is not set")
            return false
        }

        try {
            val timeout = timeoutStr.toInt
            intervalMillis = timeout * 1000 / 4

            if (intervalMillis < 1) {
                log.warn(s"Disabling watchdog: Invalid WDOG_TIMEOUT value: $timeoutStr")
                false
            } else {
                pipePath = FileSystems.getDefault().getPath(pipeStr);
                true
            }
        } catch {
            case e: NumberFormatException =>
                log.warn(s"Disabling watchdog, invalid WDOG_TIMEOUT value: $timeoutStr")
                false
        }
    }

    private def openPipe(): Boolean = {
        try {
            pipe = FileChannel.open(pipePath, StandardOpenOption.WRITE)
            log.info(s"Opened pipe to watchdog process at $pipePath")
            true
        } catch {
            case e: IOException =>
                log.warn("Failed to open pipe at " + pipePath, e)
                false
        }
    }

    def start(initializationWitness: Promise[_]) {
        if (running)
            return
        if (!readConfig())
            return
        if (!openPipe())
            return

        log.info("Starting watchdog thread")
        running = true
        Runtime.getRuntime.addShutdownHook(shutdownHook)
        timer = new Timer("watchdog", true)
        timer.scheduleAtFixedRate(tick, 0, intervalMillis)

        timer.schedule(initializationTimeout(initializationWitness), 90 * 1000)
        initializationWitness.future.onFailure {
            case e =>
                Midolman.dumpStacks()
                timer.cancel()
                pipe.close()
        }(CallingThreadExecutionContext)
    }

    def start(): Unit = {
        start(Promise.successful(true))
    }
}
