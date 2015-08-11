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

package org.midonet.cluster.services

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.slf4j.LoggerFactory._

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.{StateStorage, Storage, ZookeeperObjectMapper}
import org.midonet.cluster.models.Federation._
import org.midonet.cluster.storage.MidonetBackendConfig

/** The trait that models a backend for Multi-Site Federation, managing all
  * relevant connections and APIs to interact with backend storages. */
abstract class FederationBackend extends AbstractService {

    /** Indicates whether the new backend stack is active */
    def isEnabled = false

    /** Provides access to the Topology storage API */
    def store: Storage

    def stateStore: StateStorage

    def curator: CuratorFramework

    /** Configures a brand new ZOOM instance. */
    final def setupBindings(): Unit = {
        List(classOf[MidoNetVtep],
             classOf[OvsdbVtep],
             classOf[VtepGroup],
             classOf[VxlanSegment]
        ).foreach(store.registerClass)

        store.declareBinding(classOf[VtepGroup], "midonet_vtep_id", CASCADE,
                             classOf[MidoNetVtep], "group_id", CLEAR)
        store.declareBinding(classOf[VtepGroup], "ovsdb_vtep_id", CASCADE,
                             classOf[OvsdbVtep], "group_id", CLEAR)
        store.declareBinding(classOf[VtepGroup], "segment_id", CASCADE,
                             classOf[VxlanSegment], "group_id", CLEAR)

        store.build()
    }
}

class FederationBackendService @Inject()(cfg: MidonetBackendConfig,
                                         override val curator: CuratorFramework)
    extends FederationBackend {

    private val log = getLogger("org.midonet.nsdb")

    private val zoom =
        new ZookeeperObjectMapper(cfg.rootKey + "/zoom/federation", curator)

    override def store: Storage = zoom

    override def stateStore: StateStorage = zoom

    override def isEnabled = cfg.federationEnabled

    protected override def doStart(): Unit = {
        log.info(s"Starting backend ${this} store: $store")
        try {
            if (isEnabled &&
                curator.getState != CuratorFrameworkState.STARTED) {
                curator.start()
            }
            if (cfg.useNewStack) {
                log.info("Setting up storage bindings")
                setupBindings()
            }
            notifyStarted()
        } catch {
            case e: Exception =>
                log.error("Failed to start federation backend service", e)
                this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        if (curator.getState != CuratorFrameworkState.STOPPED) {
            curator.close()
        }
        notifyStopped()
    }
}
