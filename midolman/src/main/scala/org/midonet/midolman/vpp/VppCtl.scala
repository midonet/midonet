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

package org.midonet.midolman.vpp

import java.util.concurrent.TimeoutException

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

import com.typesafe.scalalogging.Logger

import org.midonet.util.process.ProcessHelper
import org.midonet.util.process.ProcessHelper.OutputStreams.{StdError, StdOutput}

object VppCtl {
    final val DefaultTimeout = 1 minute
}

class VppCtl(log: Logger, timeout: Duration = VppCtl.DefaultTimeout)
            (implicit ec: ExecutionContext) {
    def exec(vppCommand: String): Future[_] = {
        val command = s"vppctl $vppCommand"
        log debug s"Executing command: `$command`"

        val process = ProcessHelper.newProcess(command)
            .logOutput(log.underlying, "vppctl", StdOutput, StdError)
            .run()

        val promise = Promise[Unit]
        Future {
            if (!process.waitFor(timeout.toMillis, MILLISECONDS)) {
                process.destroy()
                log warn s"Command `$command` timed out"
                throw new TimeoutException(s"Command `$command` timed out")
            }
            log debug s"Command `$command` exited with code ${process.exitValue()}"
            if (process.exitValue == 0) {
                promise.trySuccess(())
            } else {
                promise.tryFailure(new Exception(
                    s"Command failed with result ${process.exitValue}"))
            }
        }
        promise.future
    }
}
