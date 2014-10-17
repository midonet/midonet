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

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.Builder

class FutureCompanionOps[T](val f: Future.type) extends AnyVal {

    /* Schedules a future for each element in the specified traversable,
     * only creating future n + 1 when future n has finished executing. No
     * other futures are scheduled after one of them fails.
     */
    def sequentially[A, B, M[_] <: Traversable[_]](in: M[A])
                                                  (f: A => Future[B])
                                     (implicit cbf: CanBuildFrom[M[A], B, M[B]],
                                               executor: ExecutionContext)
    : Future[M[B]] = {
        val seed: Future[Builder[B, M[B]]] = Future.successful(cbf(in))
        in.foldLeft(seed)((fr, fx) => for (r <- fr; x <- f(fx.asInstanceOf[A]))
                                      yield r += x)
          .map { _.result() }
    }
}
