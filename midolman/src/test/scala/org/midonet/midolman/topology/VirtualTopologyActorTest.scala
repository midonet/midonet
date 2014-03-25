package org.midonet.midolman.topology

/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import java.util.Arrays

import com.google.inject.{Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.zookeeper.CreateMode
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, Suite}

import org.midonet.midolman.Setup
import org.midonet.midolman.config.{MidolmanConfig, ZookeeperConfig}
import org.midonet.midolman.guice.MockMonitoringStoreModule
import org.midonet.midolman.guice.cluster.ClusterClientModule
import org.midonet.midolman.guice.config.{TypedConfigModule,
                                           MockConfigProviderModule}
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.state.{Directory, ZkManager}
import org.midonet.midolman.state.zkManagers.BridgeZkManager
import org.midonet.midolman.state.zkManagers.BridgeZkManager.{BridgeConfig => ZkBridgeConfig}
import org.midonet.cluster
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest


trait VirtualTopologyActorTest extends Suite with BeforeAndAfterAll
with BeforeAndAfter {
    var injector: Injector = null
    val zkRoot = "/test/v3/midolman"

    protected def fillConfig(config: HierarchicalConfiguration): HierarchicalConfiguration = {
        //config.setProperty("midolman.midolman_root_key", zkRoot)
        config.addNodes(ZookeeperConfig.GROUP_NAME,
            Arrays.asList(new HierarchicalConfiguration.Node
            ("midolman_root_key", zkRoot)))
        config
    }

    protected def midoStore(): cluster.Client = {
        injector.getInstance(classOf[cluster.Client])
    }

    protected def bridgeMgr(): BridgeZkManager = {
        injector.getInstance(classOf[BridgeZkManager])
    }

    protected def zkMgr(): ZkManager = {
        injector.getInstance(classOf[ZkManager])
    }

    protected def zkDir(): Directory = {
        injector.getInstance(classOf[Directory])
    }

    override def beforeAll() {
        val config = fillConfig(new HierarchicalConfiguration())
        injector = Guice.createInjector(
            new MockConfigProviderModule(config),
            new MockZookeeperConnectionModule(),
            new TypedConfigModule[MidolmanConfig](classOf[MidolmanConfig]),

            new MockMonitoringStoreModule(),
            new ClusterClientModule()
        )
        /*injector.getInstance(classOf[MidolmanService]).startAndWait()
        injector.getInstance(classOf[MidostoreSetupService]).startAndWait()*/
    }

    override def afterAll {
        //system.shutdown()
    }
}

class X extends VirtualTopologyActorTest {
    def testFirst() {
        implicit val system = ActorSystem("testsystem")
        val actorRef = TestActorRef(new VirtualTopologyActor)
        val probe = TestProbe()
        initializeZKStructure()
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot)

        // TODO waiting

        // create bridge
        val bridgeId = bridgeMgr().create(new ZkBridgeConfig())

        probe.send(actorRef, new BridgeRequest(bridgeId, true))
        val bridgeCfg = new ZkBridgeConfig()
        // set gre key sequentially
        bridgeCfg.tunnelKey = 1
        //probe.expectMsg(new Bridge(bridgeId, bridgeCfg, null, null,
        //                              null, null))
        /*val receivedBridge = probe.receiveOne(Duration.Undefined)
        val expectedBridge = new Bridge(bridgeId, bridgeCfg, null, null, null,
            null, null, null, null) */
        //assert(receivedBridge == expectedBridge)


    }

    def initializeZKStructure() {

        val node = zkRoot.split("/")
        var path = "/"
        node.foreach(n => {
            if (!n.isEmpty) {
                zkDir().add(path + n, null, CreateMode.PERSISTENT)
                path += n
                path += "/"
            }
        }
        )
    }
}
