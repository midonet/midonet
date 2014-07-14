/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.util

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.inject._
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.BeforeAndAfter
import org.scalatest.FeatureSpecLike
import org.scalatest.GivenWhenThen
import org.scalatest.Matchers
import org.scalatest.OneInstancePerTest

import org.midonet.cluster.data.Port
import org.midonet.cluster.services.MidostoreSetupService
import org.midonet.midolman.{NotYet, Ready}
import org.midonet.midolman.util.mock.MockMidolmanActors
import org.midonet.midolman.services.MidolmanService
import org.midonet.midolman.simulation.{PacketContext, Coordinator, CustomMatchers}
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.WildcardMatch

/**
 * A base trait to be used for new style Midolman simulation tests with Midolman
 * Actors.
 */
trait MidolmanSpec extends FeatureSpecLike
        with VirtualConfigurationBuilders
        with Matchers
        with BeforeAndAfter
        with GivenWhenThen
        with CustomMatchers
        with MockMidolmanActors
        with MidolmanServices
        with VirtualTopologyHelper
        with OneInstancePerTest {
    var injector: Injector = null

    /**
     * Override this function to perform a custom set-up needed for the test.
     */
    protected def beforeTest() { }

    /**
     * Override this function to perform a custom shut-down operations needed
     * for the test.
     */
    protected def afterTest() { }

    before {
        try {
            val config = fillConfig(new HierarchicalConfiguration)
            injector = Guice.createInjector(getModules(config))

            actorsService.register(registerActors)

            injector.getInstance(classOf[MidostoreSetupService]).startAndWait()
            injector.getInstance(classOf[MidolmanService]).startAndWait()

            beforeTest()
        } catch {
            case e: Throwable => fail(e)
        }
    }

    after {
        afterTest()
        actorSystem.shutdown()
    }

    protected def fillConfig(config: HierarchicalConfiguration)
            : HierarchicalConfiguration = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    def sendPacket(t: (Port[_,_], Ethernet)): SimulationResult =
        sendPacket(t._1, t._2)

    def sendPacket(port: Port[_,_], pkt: Ethernet): SimulationResult =
        sendPacket(packetContextFor(pkt, port.getId))

    def sendPacket(pktCtx: PacketContext): SimulationResult = {
        new Coordinator(pktCtx) simulate() match {
            case Ready(r) => r
            case NotYet(f) =>
                Await.result(f, 3 seconds)
                sendPacket(pktCtx)
        }
    }

    def makeWildcardMatch(port: Port[_,_], pkt: Ethernet) =
        WildcardMatch.fromEthernetPacket(pkt)
            .setInputPortUUID(port.getId)
}
