/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.UUID

import scala.collection.mutable.HashMap
import scala.concurrent.{Promise, Future}

import akka.actor.Props
import akka.testkit.TestActorRef

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.topology.{LocalPortActive, PortActivationStateMachine}
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class PortActivationStateMachineTestCase extends MidolmanSpec {

    var actorRef: TestActorRef[TestablePortActivationStateMachine] = _

    def actor = actorRef.underlyingActor

    implicit def longToUUID(id: Long) = new UUID(0, id);
    implicit def uuidToLong(id: UUID) = id.getLeastSignificantBits;

    override def beforeTest() {
        actorRef = TestActorRef(Props(
            new TestablePortActivationStateMachine()))(actorSystem)

        actor should not be null
    }

    feature("Testing the port activation machine.") {
        scenario("Testing the processing of a single message.") {
            beginPortActivation(0L, 1, 0)
            endPortActivation(0L, false, 1, 1)
        }

        scenario("Testing sequential processing of equal port IDs.") {
            beginPortActivation(0L, 1, 0)
            endPortActivation(0L, false, 1, 1)
            beginPortActivation(0L, 2, 1)
            endPortActivation(0L, false, 2, 2)
            beginPortActivation(0L, 3, 2)
            endPortActivation(0L, false, 3, 3)
        }

        scenario("Testing sequential processing of different port IDs.") {
            beginPortActivation(0L, 1, 0)
            endPortActivation(0L, false, 1, 1)
            beginPortActivation(1L, 2, 1)
            endPortActivation(1L, false, 2, 2)
        }

        scenario("Testing simultaneous processing of equal port IDs.") {
            beginPortActivation(0L, 1, 0)
            beginPortActivation(0L, 1, 0)
            beginPortActivation(0L, 1, 0)
            endPortActivation(0L, true, 2, 1)
            endPortActivation(0L, true, 3, 2)
            endPortActivation(0L, false, 3, 3)
        }

        scenario("Testing simultaneous processing of different port IDs.") {
            beginPortActivation(0L, 1, 0)
            beginPortActivation(1L, 2, 0)
            beginPortActivation(2L, 3, 0)
            endPortActivation(0L, false, 3, 1)
            endPortActivation(1L, false, 3, 2)
            endPortActivation(2L, false, 3, 3)
        }


        scenario("Testing simultaneous and sequential processing of equal and" +
                " different port IDs.") {
            beginPortActivation(0L, 1, 0)
            beginPortActivation(1L, 2, 0)
            beginPortActivation(0L, 2, 0)
            endPortActivation(0L, true, 3, 1)
            beginPortActivation(0L, 3, 1)
            beginPortActivation(1L, 3, 1)
            endPortActivation(0L, true, 4, 2)
            endPortActivation(1L, true, 5, 3)
            endPortActivation(0L, false, 5, 4)
            endPortActivation(1L, false, 5, 5)
        }
    }

    /**
     * Begins a port activation.
     * @param id The port identifier.
     * @param started The expected number of started messages.
     * @param finished The expected number of finished messages.
     */
    def beginPortActivation(id: UUID, started: Int, finished: Int) {
        When(s"Sending a port active message with ID $id.")
        actorRef ! LocalPortActive(id, false)
        Then("The actor should be processing a message with this ID.")
        actor.isProcessing(id) should be (true)
        And(s"The number of started messages should be $started.")
        actor.started should be (started)
        And(s"The number of finished messages should be $finished.")
        actor.finished should be (finished)
    }

    /**
     * Ends a port activation.
     * @param id The port identifier.
     * @param expected Whether after completion, the state machine should be
     *                 processing another message with the same port ID.
     * @param started The expected number of started messages.
     * @param finished The expected number of finished messages.
     */
    def endPortActivation(id: UUID, expected: Boolean, started: Int,
                          finished: Int) {
        When("The port activation operation completes.")
        Then("The operation should have completed successfully.")
        actor.complete(id) should be (true)
        And(s"The actor processing another message should be $expected.")
        actor.isProcessing(id) should be (expected)
        And(s"The number of started messages should be $started.")
        actor.started should be (started)
        And(s"The number of finished messages should be $finished.")
        actor.finished should be (finished)
    }

    sealed class TestablePortActivationStateMachine
            extends PortActivationStateMachine {

        private val promises = HashMap[UUID, Promise[UUID]]()
        private var startedCount = 0
        private var finishedCount = 0

        override def handlePortActivation(msg: LocalPortActive): Future[_] = {
            And("The state machine should not process the same port ID.")
            promises should not contain msg.portID

            val promise = Promise[UUID]

            promises += (msg.portID -> promise)

            startedCount = startedCount + 1

            promise future
        }

        def isProcessing(portID: UUID) = promises.contains(portID)

        def started = startedCount

        def finished = finishedCount

        def complete(portID: UUID): Boolean = {
            var result = true
            promises.get(portID) match {
                case Some(value) =>
                    promises -= portID
                    finishedCount = finishedCount + 1
                    value success portID
                case None => result = false
            }
            result
        }
    }
}
