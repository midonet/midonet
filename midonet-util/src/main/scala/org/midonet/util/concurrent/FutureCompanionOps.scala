/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
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
