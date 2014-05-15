/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.util.mock

import java.util.UUID

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import com.google.inject._
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest._

import org.midonet.midolman.{MockScheduler, Referenceable}
import org.midonet.midolman.guice._
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.guice.cluster.ClusterClientModule
import org.midonet.midolman.guice.config.ConfigProviderModule
import org.midonet.midolman.guice.datapath.MockDatapathModule
import org.midonet.midolman.guice.serialization.SerializationModule
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.midolman.version.guice.VersionModule
import org.midonet.util.concurrent._

trait MockMidolmanActors {
    this: Suite =>

    protected[this] val actorsService = new MockMidolmanActorsService
    implicit def actorSystem: ActorSystem = actorsService.system
    implicit def executionContext: ExecutionContext = ExecutionContext.callingThread

    def scheduler = actorSystem.scheduler.asInstanceOf[MockScheduler]

    // These methods can be overridden by each class mixing MockMidolmanActors
    // to add custom operations before or after each test
    protected def registerActors: List[(Referenceable, () => MessageAccumulator)] = List()

    implicit def toActorRef(ref: Referenceable): TestActorRef[MessageAccumulator] =
        actorsService.actor(ref)
    implicit def toMessageAccumulator(ref: Referenceable): MessageAccumulator =
        toActorRef(ref).underlyingActor
    implicit def toTypedActor(ref: Referenceable) = new {
        def as[A] =
            toMessageAccumulator(ref).asInstanceOf[A with MessageAccumulator]
    }

    protected def getModules(config: HierarchicalConfiguration) = {
        List(
            new VersionModule(),
            new SerializationModule(),
            new ConfigProviderModule(config),
            new MockDatapathModule(),
            new MockCacheModule(),
            new MockZookeeperConnectionModule(),
            new AbstractModule {
                def configure() {
                    bind(classOf[HostIdProviderService])
                            .toInstance(new HostIdProviderService() {
                        val hostId = UUID.randomUUID()
                        def getHostId: UUID = hostId
                    })
                }
            },
            new MockMonitoringStoreModule(),
            new ClusterClientModule(),
            new MidolmanActorsModule {
                override def configure() {
                    bind(classOf[MidolmanActorsService])
                        .toInstance(actorsService)
                    expose(classOf[MidolmanActorsService])
                }
            },
            new ResourceProtectionModule(),
            new MidolmanModule(),
            new PrivateModule {
                override def configure() {
                    bind(classOf[InterfaceScanner])
                        .to(classOf[MockInterfaceScanner]).asEagerSingleton()
                    expose(classOf[InterfaceScanner])
                }
            }
        )
    }
}
