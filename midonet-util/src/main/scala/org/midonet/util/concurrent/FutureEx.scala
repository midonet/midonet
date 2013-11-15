package org.midonet.util.concurrent

import akka.dispatch.{Promise, ExecutionContext, Future}
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.Builder

object FutureEx {

    def sequence[A, B, M[_] <: Traversable[_]](in: M[A])(f: A => Future[B])
                                (implicit cbf: CanBuildFrom[M[A], B, M[B]],
                                          executor: ExecutionContext)
    : Future[M[B]] = {
        val seed: Future[Builder[B, M[B]]] = Promise.successful(cbf(in))
        in.foldLeft(seed)((fr, fx) => for (r <- fr; x <- f(fx.asInstanceOf[A]))
                                      yield r += x)
          .map { _.result() }
    }
}
