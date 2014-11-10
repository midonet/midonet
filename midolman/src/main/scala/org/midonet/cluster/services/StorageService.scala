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

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology
import org.midonet.midolman.state.{Directory, StateAccessException}
import org.midonet.midolman.version.DataWriteVersion
import org.midonet.midolman.{Setup, SystemDataProvider}

class StorageService extends AbstractService {

    @Inject protected var directory: Directory = _
    @Inject protected var config: ZookeeperConfig = _
    @Inject protected var systemDataProvider: SystemDataProvider = _
    @Inject protected var curator: CuratorFramework = _
    @Inject protected var store: Storage = _

    protected override def doStart(): Unit = {
        try {
            val rootKey: String = config.getZkRootPath
            Setup.ensureZkDirectoryStructureExists(directory, rootKey)
            verifyVersion()
            verifySystemState()
            if (config.getCuratorEnabled) {
                curator.start()
            }
            buildStorage()
            notifyStarted()
        }
        catch {
            case e: Exception => this.notifyFailed(e)
        }
    }

    @throws(classOf[StateAccessException])
    protected def verifySystemState(): Unit = {
        if (systemDataProvider.systemUpgradeStateExists) {
            throw new RuntimeException(
                "Midolman is locked for upgrade. Please restart when upgrade " +
                "is complete.")
        }
    }

    @throws(classOf[StateAccessException])
    protected def verifyVersion(): Unit = {
        if (!systemDataProvider.writeVersionExists) {
            systemDataProvider.setWriteVersion(DataWriteVersion.CURRENT)
        }
        if (systemDataProvider.isBeforeWriteVersion(DataWriteVersion.CURRENT)) {
            throw new RuntimeException(
                "Midolmans version (" + DataWriteVersion.CURRENT +
                ") is lower than the write version (" +
                systemDataProvider.getWriteVersion + ").")
        }
    }

    protected def buildStorage(): Unit = {

        List(classOf[Topology.Port], classOf[Topology.TunnelZone],
             classOf[Topology.Host]).foreach(clazz => store.registerClass(clazz)
        )

        store.build()
    }

    protected def doStop(): Unit = {
        curator.close()
        notifyStopped()
    }
}
