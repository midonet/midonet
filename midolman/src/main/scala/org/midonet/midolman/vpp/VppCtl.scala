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

import scala.concurrent.{ExecutionContext, Future}

import org.midonet.containers.ContainerCommons

trait VppCtl extends ContainerCommons {

    private val logFunction = (line: String) => log.debug(s"vppctl: $line")

    def vppCtl(vppCommand: String)(implicit ec: ExecutionContext): Future[_] = {
        val command = s"vppctl $vppCommand"
        log debug s"Executing command: `$command`"

        Future {
            val code = execute(command, logFunction)
            log debug s"Command `$command` exited with code $code"
            if (code != 0)
                throw new Exception(s"Command failed with result $code")
        }
    }
}
