/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring

import com.midokura.midolman._
import com.midokura.midonet.cluster.data.{Ports, Bridge}
import com.yammer.metrics.Metrics
import com.yammer.metrics.core.Metric
import com.yammer.metrics.core.MetricName
import com.yammer.metrics.core.MetricsRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.{BeforeTest, Test}
import java.util._
import org.hamcrest.MatcherAssert.assertThat
import java.util
import collection.{mutable, immutable}
import scala.collection.JavaConversions._
import store.{Store, MockCassandraStore}
import com.midokura.sdn.dp.{Datapath, Ports}
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge,
Ports => ClusterPorts}
import com.midokura.midolman.topology.rcu.{Host => RCUHost}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midolman.DatapathController.DatapathPortChangedEvent
import topology.VirtualToPhysicalMapper
import topology.VirtualToPhysicalMapper.LocalPortActive
import com.midokura.util.functors.Callback0
import com.midokura.tools.timed.Timed
import com.midokura.util.Waiters.waitFor

object MonitoringTest {
}

class MonitoringTest extends MidolmanTestCase {

  private final val log: Logger = LoggerFactory.getLogger(classOf[MonitoringTest])

  var monitoringAgent: MonitoringAgent = null
  var registry: MetricsRegistry = null


  @Test def testActualMonitoring {

    val jvmNames = immutable.Set(
      "OpenFileDescriptorCount", "ThreadCount", "FreePhysicalMemorySize",
      "TotalPhysicalMemorySize", "ProcessCPUTime", "TotalSwapSpaceSize", "MaxHeapMemory", "UsedHeapMemory",
      "CommittedHeapMemory", "AvailableProcessors", "FreeSwapSpaceSize", "SystemLoadAverage", "txBytes", "rxBytes",
      "rxPackets","txPackets")

    val store : Store = injector.getInstance(classOf[Store])
    val cassandraStore = store.asInstanceOf[MockCassandraStore]
    assertThat("Initial ports are empty", cassandraStore.getTargets.isEmpty);

    var called : Boolean = false;
    val host = new Host(hostId()).setName("myself")
    clusterDataClient().hostsCreate(hostId(), host)

    initializeDatapath()

    // make a bridge, and a port.
    val bridge = new ClusterBridge().setName("test")
    bridge.setId(clusterDataClient().bridgesCreate(bridge))
    val port = ClusterPorts.materializedBridgePort(bridge)


    port.setId(clusterDataClient().portsCreate(port))

    cassandraStore.subscribeToChangesRegarding(port.getId.toString, new Callback0 {
      def call() {
        called = true;
      }
    })

    clusterDataClient().hostsAddVrnPortMapping(host.getId, port.getId, "tapDevice")

    // make sure the monitoring agent receives data for the expected port.
    probeByName(MonitoringActor.Name).expectMsgType[DatapathController.PortStats].portID.toString should be (port.getId.toString)


    waitFor("Wait for the MidoReporter to write to Cassandra.",10000, 500,  new Timed.Execution[Boolean]{
      protected def _runOnce() {
          setCompleted(called);
          setResult(called)
      }
    })

    assertThat("Cassandra contains data about the port", called)

    // get all the metrics names
    registry = Metrics.defaultRegistry;
    val allMetrics: Map[MetricName, Metric] = registry.allMetrics
    val names = mutable.Set.empty[String]

    for (metricName <- allMetrics.keySet()) {
      names += metricName.getName
    }

    assertThat("The registry contains all the expected metrics.", (names.diff(jvmNames)).isEmpty )

    // clean stuff.
    registry.allMetrics().keys.foreach((arg: MetricName) => registry.removeMetric(arg))
  }



}