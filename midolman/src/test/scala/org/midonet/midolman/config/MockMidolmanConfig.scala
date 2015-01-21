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

package org.midonet.midolman.config

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.midolman.util.MidolmanSpec

object MockMidolmanConfig extends MidolmanConfig {
    override def getArpExpirationSeconds: Int = 10
    override def getArpRetryIntervalSeconds: Int = 60
    override def getArpStaleSeconds: Int = 1800
    override def getArpTimeoutSeconds: Int = 3600

    override def getCassandraCluster: String = "midonet"
    override def getCassandraReplicationFactor: Int = 1
    override def getCassandraServers: String = "127.0.0.1:9042"

    override def getControlPacketsTos: Int = 46 << 2
    override def getDatapathMaxFlowCount: Int = 10000
    override def getDatapathMaxWildcardFlowCount: Int = 10000
    override def getGlobalIncomingBurstCapacity: Int = 20000
    override def getMaxMessagesPerBatch: Int = 200
    override def getSendBufferPoolBufSizeKb: Int = 16
    override def getSendBufferPoolInitialSize: Int = 128
    override def getSendBufferPoolMaxSize: Int = 512
    override def getTunnelIncomingBurstCapacity: Int = 20000
    override def getVmIncomingBurstCapacity: Int = 8000
    override def getVtepIncomingBurstCapacity: Int = 2000
    override def getVxLanOverlayUdpPort: Int = 6677
    override def getVxLanVtepUdpPort: Int = 4789

    override def getHaproxyFileLoc: String = "/etc/midolman/l4lb/"
    override def getHealthMonitorEnable: Boolean = false
    override def getNamespaceCleanup: Boolean = false
    override def getNamespaceSuffix: String = "_MN"

    override def getDashboardEnabled: Boolean = false
    override def getDatapath: String = "midonet"
    override def getDhcpMtu: Int = MidolmanSpec.TestDhcpMtu
    override def getFlowExpirationInterval: Int = 10000
    override def getIdleFlowToleranceInterval: Int = 10000
    override def getInputChannelThreading: String = "one_to_many"
    override def getMacPortMappingExpireMillis: Int = 30000
    override def getMaxBgpPeerRoutes: Int = 200
    override def getMidolmanBGPConnectRetry: Int = 120
    override def getMidolmanBGPEnabled: Boolean = true
    override def getMidolmanBGPHoldtime: Int = 180
    override def getMidolmanBGPKeepAlive: Int = 60
    override def getMidolmanBGPPortStartIndex: Int = 0
    override def getMidolmanBridgeArpEnabled: Boolean = false
    override def getMidolmanCacheType: String = "cassandra"
    override def getMidolmanDisconnectedTtlSeconds: Int = 30
    override def getMidolmanTopLevelActorSupervisor: String = "resume"
    override def getNumOutputChannels: Int = 1
    override def getSimulationThreads: Int = 1
    override def pathToBGPD: String = "/usr/sbin"
    override def pathToBGPDConfig: String = "/etc/quagga"
    override def pathToJettyXml: String = "/etc/midolman/jetty/etc/jetty.xml"

    override def getClusterStorageEnabled: Boolean = false
    override def getCuratorEnabled: Boolean = false
    override def getZkGraceTime: Int = ZookeeperConfig.DEFAULT_GRACETIME_MS
    override def getZkHosts: String = ZookeeperConfig.DEFAULT_HOSTS
    override def getZkRootPath: String = "/midonet"
    override def getZkSessionTimeout: Int = ZookeeperConfig.DEFAULT_TIMEOUT_MS
}
