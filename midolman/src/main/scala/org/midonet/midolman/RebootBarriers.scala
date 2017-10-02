/*
 * Copyright 2017 Midokura SARL
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

import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.barriers.DistributedBarrier

import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.HostIdGenerator
import org.midonet.util.logging.Logger

object RebootBarriers {

    val Log = Logger("org.midonet.fast_reboot")

}

class RebootBarriers(zk: CuratorFramework,
                     config: MidonetBackendConfig) {

    import RebootBarriers._

    private val path = s"${config.rootKey}/fast_reboot/${HostIdGenerator.getHostId}"

    class Barrier(path: String)
        extends DistributedBarrier(zk, path) {

        override def setBarrier(): Unit = {
            try {
                super.setBarrier()
            } catch {
                case NonFatal(e) =>
                    Log.warn(s"Failed to set barrier $path: ${e.getMessage}")
            }
        }

        override def removeBarrier(): Unit = {
            try {
                super.removeBarrier()
            } catch {
                case NonFatal(e) =>
                    Log.warn(s"Failed to remove barrier $path: ${e.getMessage}")
            }
        }

        override def waitOnBarrier(maxWait: Long, unit: TimeUnit): Boolean = {
            try {
                super.waitOnBarrier(maxWait, unit)
            } catch {
                case NonFatal(e) =>
                    Log.warn(s"Error while waiting on a barrier: ${e.getMessage}")
                    false
            }
        }

    }

    val stage1 = new Barrier(s"$path/stage1")

    val stage2 = new Barrier(s"$path/stage2")

    val stage3 = new Barrier(s"$path/stage3")

    def clear() = {
        Log.info("Clearing fast reboot barriers")

        Seq(stage1) foreach { stage =>
            stage.removeBarrier()
            stage.waitOnBarrier()
        }
        Log.info("Fast reboot barriers cleared succesfully.")
    }

}
