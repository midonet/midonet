package org.midonet

import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.services.{MidonetBackendService, MidonetBackend}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil
import org.midonet.conf.MidoNodeConfigurator
import org.midonet.midolman.Midolman

import org.midonet.cluster.models.Topology._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.sys.process._

object TestMidolman {

    val ZK_PORT = 2181
    val TESTING_CONF = ConfigFactory.parseString(
        s"""
        |zookeeper.zookeeper_hosts = "127.0.0.1:$ZK_PORT"
        |""".stripMargin)

    val zk = new TestingServer(ZK_PORT)

    val configurator = MidoNodeConfigurator.apply(TESTING_CONF)

    def getStorage = {
        val config = new MidonetBackendConfig(configurator.runtimeConfig)
        val curator = CuratorFrameworkFactory.newClient(
             config.hosts, new ExponentialBackoffRetry(1000, 3))
        val metrics = new MetricRegistry()
        MidonetBackend.pretendToBeCluster()
        val backend = new MidonetBackendService(config, curator, metrics)
        backend.startAsync()
        backend.awaitRunning()
        backend.store
    }

    def startMidolman(): Unit = {
        zk.start()
        val midolman = Midolman.getInstance()
        midolman.run(configurator)
    }

    def main (args: Array[String]): Unit = {
        zk.start()
        startMidolman()
        val store = getStorage
        val result = TestPingThroughBridge.testPingThroughBridge(store)
        if (result) {
            System.out.println("!!!!!!!! TEST PASSED !!!!!!!")
            System.exit(0)
        } else {
            System.out.println("!!!!!!!! TEST FAILED !!!!!!!")
            System.exit(-1)
        }
    }
}

object TestPingThroughBridge {

    val waitTime = Duration.apply(10, TimeUnit.SECONDS)

    def testPingThroughBridge(store: Storage): Boolean  = {
        val host = Await.result(store.getAll(classOf[Host]), waitTime).last
        NSUtils.preFabNamespace("LEFT", "1.1.1.1/24", "1.1.1.2/24")
        NSUtils.preFabNamespace("RIGHT", "1.1.1.2/24", "1.1.1.1/24")

        val networkId = UUIDUtil.randomUuidProto
        var network = Network.newBuilder()
            .setId(networkId)
            .setAdminStateUp(true)
            .setName("test network")
            .build()
        store.create(network)

        val lpId = UUIDUtil.randomUuidProto
        val leftPort = Port.newBuilder()
            .setId(lpId)
            .setNetworkId(networkId)
            .setInterfaceName("LEFT_dp")
            .setHostId(host.getId)
            .build()
        store.create(leftPort)

        val rightPort = Port.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setNetworkId(networkId)
            .setInterfaceName("RIGHT_dp")
            .setHostId(host.getId)
            .build()
        store.create(rightPort)

        val s1 = NSUtils.ping("LEFT", "1.1.1.2")
        val s2 = NSUtils.ping("RIGHT", "1.1.1.1")

        NSUtils.delNamespace("LEFT")
        NSUtils.delNamespace("RIGHT")

        s1 && s2
    }
}

object NSUtils {

    def preFabNamespace(name: String, ip: String, gateway: String): Unit = {
        val DP = s"${name}_dp"
        val NS = s"${name}_ns"
        s"ip netns add $name".!
        s"ip link add name $DP type veth peer name $NS".!
        s"ip link set $DP up".!
        s"ip link set $NS netns $name".!
        s"ip netns exec $name ip link set up dev $NS".!
        s"ip netns exec $name ip address add $ip dev $NS".!
        s"ip netns exec $name ip link set up dev lo".!
        s"ip netns exec $name ip route add default via $gateway dev $NS".!
    }

    def delNamespace(name: String): Unit = {
        s"ip netns del $name".!
    }

    def ping(fromNs: String, toIp: String): Boolean = {
        try {
            val cmd = s"ip netns exec $fromNs ping -w1 -c1 $toIp"
            System.out.println(cmd)
            cmd.!
            true
        } catch {
            case re: RuntimeException => false
        }
    }
}
