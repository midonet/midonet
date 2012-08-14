/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.vrn

import com.google.inject.{Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfter, Suite, BeforeAndAfterAll}
import com.midokura.midolman.guice.ComponentInjectorHolder
import com.midokura.midolman.guice.zookeeper.MockZookeeperConnectionModule
import com.midokura.midolman.guice.reactor.ReactorModule
import com.midokura.midolman.guice.config.{TypedConfigModule, MockConfigProviderModule}
import com.midokura.midolman.config.MidolmanConfig
import akka.testkit.{TestProbe, TestActorRef}
import com.midokura.midolman.state.zkManagers.BridgeZkManager
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import com.midokura.midolman.state.{ZkManager, Directory}
import org.apache.zookeeper.CreateMode
import com.midokura.midolman.Setup
import akka.util.Duration
import com.midokura.midonet.cluster
import com.midokura.midolman.guice.cluster.ClusterClientModule
import com.midokura.midolman.simulation.Bridge
import com.midokura.midolman.topology.{BridgeRequest, VirtualTopologyActor}

//class VirtualTopologyActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpec with MustMatchers with BeforeAndAfterAll {
  trait VirtualTopologyActorTest extends Suite with BeforeAndAfterAll with BeforeAndAfter {
    var injector: Injector = null
    val zkRoot = "/test/v3/midolman"

  protected def fillConfig(config: HierarchicalConfiguration): HierarchicalConfiguration = {
      config.setProperty("midolman.midolman_root_key", zkRoot)
      config
    }
    /*
    protected def dpConn(): OvsDatapathConnection = {
      injector.getInstance(classOf[OvsDatapathConnection])
    }

    protected def actors(): ActorSystem = {
      injector.getInstance(classOf[MidolmanActorsService]).system
    }*/

    protected def midoStore(): cluster.Client = {
      injector.getInstance(classOf[cluster.Client])
    }

    protected def bridgeMgr(): BridgeZkManager = {
      injector.getInstance(classOf[BridgeZkManager])
    }

    protected def zkMgr() : ZkManager = {
      injector.getInstance(classOf[ZkManager])
    }

    protected def zkDir() : Directory = {
      injector.getInstance(classOf[Directory])
    }
    /*
    protected def hostId(): UUID = {
      UUID.fromString("067e6162-3b6f-4ae2-a171-2470b63dff00")
    }*/
    /*
    def topActor(name: String): ActorRef = {
      actors().actorFor(actors() / name)
    } */
    override def beforeAll() {
      val config = fillConfig(new HierarchicalConfiguration())
      injector = Guice.createInjector(
        new MockConfigProviderModule(config),
        new MockZookeeperConnectionModule(),
        new TypedConfigModule[MidolmanConfig](classOf[MidolmanConfig]),

        new ReactorModule(),
        new ClusterClientModule()
      )

      /*injector.getInstance(classOf[MidolmanService]).startAndWait()
      injector.getInstance(classOf[MidostoreSetupService]).startAndWait()*/
      ComponentInjectorHolder.setInjector(injector)
    }

  override def afterAll {
    //system.shutdown()
  }
}

class VirtualTopologyProbe extends TestProbe(ActorSystem("testsystem")){

    def expectBridge(x: Bridge) {

    }

}

class X extends VirtualTopologyActorTest {
  def testFirst() {
    implicit val system = ActorSystem("testsystem")
    val actorRef = TestActorRef(new VirtualTopologyActor())
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
    probe.expectMsg(new Bridge(bridgeId, bridgeCfg, null, null))
    val foo = probe.receiveOne(Duration.Undefined)
    val mao = new Bridge(bridgeId, bridgeCfg, null, null)
    if(foo == mao){
        val pp = 0
    }

    val bar = 2
  }

    def initializeZKStructure(){

        val node = zkRoot.split("/")
        var path = "/"
        node.foreach(n => {
            if(!n.isEmpty){
                zkDir().add(path+n, null, CreateMode.PERSISTENT)
                path+=n
                path+="/"
            }
        }
        )
    }
}
