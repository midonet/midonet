/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.util.concurrent

import akka.actor.Actor

import rx.{Subscriber, Observer}

object ReactiveActor {

    sealed trait ReactiveAction
    case class OnNext[+I <: AnyRef, +D <: AnyRef](id: I, value: D)
        extends ReactiveAction
    case class OnError[+I <: AnyRef](id: I, e: Throwable) extends ReactiveAction
    case class OnCompleted[+I <: AnyRef](id: I) extends ReactiveAction

}

trait ReactiveActor[D <: AnyRef] extends Actor {

    import org.midonet.util.concurrent.ReactiveActor._

    protected class ReactiveSubscriber[+I <: AnyRef](id: I)
            extends Subscriber[D] {
        final override def onCompleted(): Unit = {
            self ! OnCompleted(id)
        }
        final override def onError(e: Throwable): Unit = {
            self ! OnError(id, e)
        }
        final override def onNext(t: D): Unit = {
            self ! OnNext(id, t)
        }
    }
}
