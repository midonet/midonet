package com.midokura.midolman.topology

/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

import akka.actor.ActorSystem
import akka.testkit.{TestProbe, TestActorRef}
import akka.util.Duration
import com.midokura.midolman.simulation.Bridge
import com.google.inject.{Guice, Injector}
import com.midokura.midolman.config.{MidolmanConfig, ZookeeperConfig}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.zookeeper.CreateMode
import org.scalatest.{BeforeAndAfter, Suite, BeforeAndAfterAll}
import com.midokura.midolman.guice.cluster.ClusterClientModule
import com.midokura.midolman.guice.config.{TypedConfigModule,
MockConfigProviderModule}
import com.midokura.midolman.guice.reactor.ReactorModule
import com.midokura.midolman.guice.zookeeper.MockZookeeperConnectionModule
import com.midokura.midolman.state.zkManagers.BridgeZkManager
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import com.midokura.midolman.state.{ZkManager, Directory}
import com.midokura.midonet.cluster
import java.util.Arrays
import com.midokura.midolman.Setup
import com.midokura.midolman.guice.ComponentInjectorHolder


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

            new ReactorModule(),
            new ClusterClientModule()
        )
        ComponentInjectorHolder.setInjector(injector)
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
        Setup.createZkDirectoryStructure(zkDir(), zkRoot)

        // TODO waiting

        // create bridge
        val bridgeId = bridgeMgr().create(new BridgeConfig())

        probe.send(actorRef, new BridgeRequest(bridgeId, true))
        val bridgeCfg = new BridgeConfig()
        // set gre key sequentially
        bridgeCfg.greKey = 1
        //probe.expectMsg(new Bridge(bridgeId, bridgeCfg, null, null,
        //                              null, null))
        val receivedBridge = probe.receiveOne(Duration.Undefined)
        val expectedBridge = new Bridge(bridgeId, bridgeCfg, null, null, null, null)
        assert(receivedBridge == expectedBridge)


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
