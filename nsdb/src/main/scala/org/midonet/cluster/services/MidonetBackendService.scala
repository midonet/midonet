/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.cluster.services

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.storage.MidonetBackendConfig

abstract class MidonetBackend extends AbstractService

/** Class responsible for providing services to access to the new Storage
  * services. */
class MidonetBackendService @Inject() (cfg: MidonetBackendConfig,
                                       curator: CuratorFramework)
    extends MidonetBackend {

    protected override def doStart(): Unit = {
        try {
            curator.start()
            notifyStarted()
        } catch {
            case e: Exception => this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        curator.close()
        notifyStopped()
    }
}
