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
package org.midonet

import java.util.ArrayList

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration.FiniteDuration

import akka.actor.{ActorIdentity, Identify, ActorSystem, ActorRef}
import akka.pattern.ask
import akka.util.Timeout

import org.midonet.util.functors.Callback0

package object midolman extends ReferenceableSupport {
    object CheckBackchannels

    implicit class CallbackExecutor(val callbacks: ArrayList[Callback0]) extends AnyVal {
        def runAndClear(): Unit = {
            var i = 0
            while (i < callbacks.size()) {
                callbacks.get(i).call()
                i += 1
            }
            callbacks.clear()
        }
    }

    implicit class ActorEx(val actor: ActorRef) extends AnyVal {
        def awaitStart(duration: FiniteDuration)
                      (implicit actorSystem: ActorSystem,
                                ec: ExecutionContext): ActorRef = {
            implicit val timeout: Timeout = duration
            Await.result(actor ? Identify(null), duration)
                 .asInstanceOf[ActorIdentity].getRef
        }
    }

    implicit class ActorSeq(val actors: Seq[ActorRef]) extends AnyVal {
        def awaitStart(duration: FiniteDuration)
                      (implicit actorSystem: ActorSystem,
                                ec: ExecutionContext): Unit = {
            implicit val timeout: Timeout = duration
            Await.result(Future.sequence(actors map (_ ? Identify(null))), duration)
        }
    }
}
