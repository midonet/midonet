/*
 * Copyright 2017,2018 Midokura SARL
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

import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.Promise
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, FileSystems, NoSuchFileException, Path,
                      StandardOpenOption}
import java.util.concurrent.{TimeoutException, TimeUnit}

import sun.nio.ch.NativeThread

import org.midonet.util.logging.Logger


object RebootBarriers {

    val Log = Logger("org.midonet.fast_reboot")

}

class RebootBarriers(val isMain: Boolean) {

    import RebootBarriers._

    private val fifoPath = "/var/run/midolman/fast_reboot.barrier"

    private var previousStage = 0  // Just for an assertion

    private var blocking = false
    private var blockingThread: Long = _

    private def toStringRole = if (isMain) "main" else "backup"

    def waitOnBarrier(stage: Int, maxWait: Long, unit: TimeUnit): Unit = {
        require(stage == previousStage + 1)
        require(unit ne null)
        require(maxWait > 0)

        previousStage = stage

        Log.info(s"Stage ${stage} (${toStringRole}) start")

        val timeoutMillis = TimeUnit.MILLISECONDS.convert(maxWait, unit)
        val path = FileSystems.getDefault().getPath(fifoPath)
        // Flip sides for consecutive stages to avoid races.
        val option = if (isMain ^ (stage % 2 == 0))
            StandardOpenOption.READ
        else
            StandardOpenOption.WRITE

        val p = Promise[Unit]()
        val thread = new Thread() {
            override def run() = {
                this.synchronized {
                    blocking = true
                    blockingThread = NativeThread.current
                }
                try {
                    /*
                     * This open() blocks until the other side is opened as well.
                     *
                     * From SUSv2 open(2):
                     *   When opening a FIFO with O_RDONLY or O_WRONLY set
                     *         :
                     *         :
                     *   An open() for reading only will block the calling thread
                     *   until a thread opens the file for writing. An open() for
                     *   writing only will block the calling thread until a thread
                     *   opens the file for reading.
                     */
                    FileChannel.open(path, option).close()
                } catch {
                    case NonFatal(e) => p.tryFailure(e)
                } finally {
                    this.synchronized {
                        blocking = false
                    }
                }
                p.trySuccess(())
            }
        }
        thread.start()
        thread.join(timeoutMillis)
        if (p.tryFailure(new TimeoutException)) {
            /*
             * Cancel the thread.
             * We remove the file so that the thread will notice ENOENT.
             * A signal is not enough as JDK unconditionally restarts open(2) on EINTR.
             *
             * NOTE: this also makes the other side of the barrier fail immediately
             * when it arrived later.  In that case, ENOENT is probably confusing.
             */
            Files.deleteIfExists(path)
            /*
             * The following interrupt() is not necessary for the common case,
             * i.e. when the thread is blocking in the open(2) system call,
             * but just in case.
             */
            thread.interrupt()
            /*
             * Interrupt the system call.
             * JDK will restart the system call and get ENOENT.
             */
            this.synchronized {
                if (blocking) {
                    NativeThread.signal(blockingThread)
                }
            }
            thread.join()
        }
        p.future.value.get match {
            case Success(_) =>
                Log.info(s"Stage ${stage} (${toStringRole}) success")
            case Failure(e) =>
                Log.error(s"Stage ${stage} (${toStringRole}) failed: ${e}")
                throw e
        }
    }
}
