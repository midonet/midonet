/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.actors

import com.midokura.midolman.guice.MidolmanActorsModule
import com.midokura.midolman.services.MidolmanActorsService
import collection.mutable
import akka.testkit.{TestActor, TestActorRef, TestKit}
import akka.actor.{ActorRef, Props, Actor}
import java.util.concurrent.LinkedBlockingDeque
import akka.testkit.TestActor.{AutoPilot, Message}

/**
 * A [[com.midokura.midolman.guice.MidolmanActorsModule]] that can will override
 * the top level actors with probes and also provide an easy easy to access the
 * actual actors internal state.
 *
 * @see [[com.midokura.midolman.MidolmanTestCase]] for an usage example.
 */
class TestableMidolmanActorsModule(probes: mutable.Map[String, TestKit],
                                   actors:mutable.Map[String, TestActorRef[Actor]])
    extends MidolmanActorsModule {

    protected override def bindMidolmanActorsService() {
        bind(classOf[MidolmanActorsService])
            .toInstance(new TestableMidolmanActorsService())
    }

    class TestableMidolmanActorsService extends MidolmanActorsService {
        protected override def makeActorRef(actorProps: Props, actorName: String): ActorRef = {
            implicit val system = actorSystem

            val targetActor = TestActorRef[Actor](actorProps, actorName + "-real")

            val testKit = new TestKit(system) {
                override lazy val testActor: ActorRef = {
                    val field = this.getClass.getSuperclass.getDeclaredField("akka$testkit$TestKit$$queue")
                    field.setAccessible(true)
                    val queue = field.get(this).asInstanceOf[LinkedBlockingDeque[Message]]
                    system.actorOf(Props(new TestActor(queue)), actorName)
                }
            }

            testKit.setAutoPilot(new AutoPilot {
                def run(sender: ActorRef, msg: Any):Option[TestActor.AutoPilot] = {
                    msg match {
                        case "stop" => None
                        case m  => targetActor.tell(m, sender); Some(this)
                    }
                }
            })

            probes.put(actorName, testKit)
            actors.put(actorName, targetActor)

            testKit.testActor
        }
    }
}


