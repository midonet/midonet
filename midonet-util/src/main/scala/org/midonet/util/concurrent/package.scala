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
package org.midonet.util

import scala.concurrent.{ExecutionContext, Future}
import akka.actor.ActorRef

package object concurrent {

    /* Note that FutureOps is a value class. No allocation will actually result
     * from the new() operation. A call such as f.continue(cont) will undergo a
     * compile-time transformation into a call on a static object, for example
     * FutureOps.MODULE$.extension$continue(f, cont).
     */
    implicit def toFutureOps[T](f: Future[T]): FutureOps[T] = new FutureOps(f)

    implicit def toCompanionFutureOps(f: Future.type): FutureCompanionOps[_] =
        new FutureCompanionOps(f)

    implicit class ExecutionContextOps(val ec: ExecutionContext.type)
            extends AnyVal {
        def callingThread = CallingThreadExecutionContext
        def onActor(actor: ActorRef) = new ActorExecutionContext(actor)
    }
}
