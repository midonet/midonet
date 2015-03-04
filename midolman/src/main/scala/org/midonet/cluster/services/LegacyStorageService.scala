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

import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.midolman.version.DataWriteVersion
import org.midonet.midolman.{Setup, SystemDataProvider}
import org.midonet.midolman.state.{StateAccessException, Directory}

class LegacyStorageService @Inject() (directory: Directory,
                                      zkCfg: MidonetBackendConfig,
                                      systemDataProvider: SystemDataProvider)
    extends AbstractService {

    protected override def doStart(): Unit = {
        notifyStarted()
        val rootKey: String = zkCfg.rootKey
        Setup.ensureZkDirectoryStructureExists(directory, rootKey)
        verifyVersion()
        verifySystemState()
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

    protected override def doStop(): Unit = notifyStopped()

}
