#
# Copyright 2015 Midokura SARL
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#    http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
from mdts.services.jmx_monitor import JMXMonitor
import logging

LOG = logging.getLogger(__name__)

class MidonetClusterJMXMonitor(JMXMonitor):

    def __init__(self):
        super(MidonetClusterJMXMonitor, self).__init__()
        self._zoom_metric_names = None

# [java.lang:type=MemoryPool
# name=G1 Old Gen
#  java.lang:type=MemoryPool
# name=Metaspace
#  midonet-metrics:name=unavailables
#  metrics:name=Zoom-/midonet/v1/zoom/0/TypeWatchersTriggered
#  midonet-metrics:name=speculative-executions
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineMeter.packetsProcessed.packets
#  midonet-metrics:name=reconnection-scheduler-task-count
#  midonet-metrics:name=retries-on-write-timeout
#  metrics:name=Zoom-/midonet/v1/zoom/0/TypeObservableList
#  midonet-metrics:name=other-errors
#  java.nio:type=BufferPool
# name=mapped
#  midonet-metrics:name=ignores-on-unavailable
#  java.lang:type=Compilation
#  java.lang:type=MemoryPool
# name=G1 Survivor Space
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineHistogram.simulationLatency
#  metrics:name=Zoom-/midonet/v1/zoom/0/ObjectObservableCountPerType
#  metrics:name=Zoom-/midonet/v1/zoom/0/readChildrenMicroSec
#  metrics:name=org.midonet.midolman.monitoring.metrics.FlowTablesMeter.datapathFlowsCreated.datapathFlows
#  midonet-metrics:name=ignores-on-read-timeout
#  java.lang:type=MemoryManager
# name=CodeCacheManager
#  midonet-metrics:name=blocking-executor-queue-depth
#  metrics:name=Zoom-/midonet/v1/zoom/0/TypeObservableCount
#  java.lang:type=GarbageCollector
# name=G1 Old Generation
#  java.lang:type=MemoryPool
# name=Code Cache
#  org.midonet.midolman:type=Metering
#  metrics:name=Zoom-/midonet/v1/zoom/0/ZKNoNodeExceptionCount
#  java.lang:type=Runtime
#  com.sun.management:type=DiagnosticCommand
#  java.nio:type=BufferPool
# name=direct
#  midonet-metrics:name=requests
#  metrics:name=Zoom-/midonet/v1/zoom/0/readMicroSec
#  midonet-metrics:name=retries-on-unavailable
#  midonet-metrics:name=ignores
#  java.lang:type=MemoryManager
# name=Metaspace Manager
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineCounter.packetsOnHold
#  midonet-metrics:name=trashed-connections
#  java.lang:type=Memory
#  metrics:name=org.midonet.midolman.monitoring.metrics.FlowTablesGauge.currentDatapathFlows
#  metrics:name=Zoom-/midonet/v1/zoom/0/ZKConnectionLostExceptionCount
#  midonet-metrics:name=open-connections
#  midonet-metrics:name=known-hosts
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineGauge.liveSimulations
#  metrics:name=Zoom-/midonet/v1/zoom/0/multiMicroSec
#  metrics:name=Zoom-/midonet/v1/zoom/0/ZKNodeExistsExceptionCount
#  midonet-metrics:name=retries
#  midonet-metrics:name=connection-errors
#  org.midonet.midolman:type=PacketTracing
#  java.lang:type=OperatingSystem
#  midonet-metrics:name=read-timeouts
#  midonet-metrics:name=connected-to
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineMeter.packetsPostponed.packets
#  metrics:name=org.midonet.midolman.monitoring.metrics.FlowTablesMeter.datapathFlowsRemoved.datapathFlowsRemoved
#  java.lang:type=ClassLoading
#  midonet-metrics:name=retries-on-read-timeout
#  metrics:name=Zoom-/midonet/v1/zoom/0/ZkConnectionState
#  java.lang:type=Threading
#  metrics:name=Zoom-/midonet/v1/zoom/0/ObjectWatchersTriggered
#  metrics:name=Zoom-/midonet/v1/zoom/0/writeMicroSec
#  java.util.logging:type=Logging
#  metrics:name=Zoom-/midonet/v1/zoom/0/ObjectObservableCount
#  metrics:name=Zoom-/midonet/v1/zoom/0/ObservablePrematureCloseCount
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineAccumulatedTime.simulationAccumulatedTime
#  java.lang:type=MemoryPool
# name=Compressed Class Space
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineMeter.packetsDropped.packets
#  midonet-metrics:name=write-timeouts
#  com.sun.management:type=HotSpotDiagnostic
#  java.lang:type=MemoryPool
# name=G1 Eden Space
#  midonet-metrics:name=task-scheduler-task-count
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineMeter.packetsSimulated.packets
#  java.lang:type=GarbageCollector
# name=G1 Young Generation
#  midonet-metrics:name=ignores-on-write-timeout
#  ch.qos.logback.classic:Name=default
# Type=ch.qos.logback.classic.jmx.JMXConfigurator
#  metrics:name=org.midonet.midolman.monitoring.metrics.PacketPipelineCounter.currentPendedPackets
#  midonet-metrics:name=executor-queue-depth
#  JMImplementation:type=MBeanServerDelegate]
        