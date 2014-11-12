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

package org.midonet.cluster.c3po

import java.util.concurrent.{TimeUnit, Executors}

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.midonet.cluster.data.neutron.{NeutronPollingThread, NeutronService}
import org.midonet.cluster.data.storage.Storage

class C3PO(curator: CuratorFramework,
           store: Storage,
           neutron: NeutronService) {


    val latchPath = "/c3po/leaderlatch/"
    val leaderLatch = new LeaderLatch(curator, latchPath)
    leaderLatch.start()

    // TODO: Make number of threads configurable.
    val scheduler = Executors.newScheduledThreadPool(1)

    val neutronPollingThread =
        new NeutronPollingThread(curator, store, neutron, hasLeadership)
    // TODO: Make interval configurable.
    scheduler.scheduleAtFixedRate(neutronPollingThread, 0, 5, TimeUnit.SECONDS)

    def hasLeadership = leaderLatch.hasLeadership
}
