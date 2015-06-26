package org.midonet.util.concurrent

import scala.concurrent.ExecutionContext

import akka.actor.{Actor, ActorRef}

/**
 * An ExecutionContext that schedules runnables on the specified ActorRef.
 * The underlying Actor should implement SingleThreadExecutionContextProvider
 * or accept Runnable messages.
 */
class ActorExecutionContext(actor: ActorRef) extends SingleThreadExecutionContext {

    override def execute(runnable: Runnable): Unit =
        actor ! runnable

    override def reportFailure(t: Throwable): Unit =
        ExecutionContext.defaultReporter(t)
}

trait SingleThreadExecutionContextProvider { this: Actor =>

    val singleThreadExecutionContext: SingleThreadExecutionContext =
        new ActorExecutionContext(self)

    def receive: Receive = {
        case r: Runnable => r.run()
    }
}
