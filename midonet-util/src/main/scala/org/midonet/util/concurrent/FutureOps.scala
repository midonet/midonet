/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util.concurrent

import scala.concurrent._
import scala.util.Try

class FutureOps[T](val f: Future[T]) extends AnyVal {

     /* Continues the computation of this future by taking the current future
      * and mapping it into another future.
      *
      *  The function `cont` is called only after the current future completes.
      *  The resulting future contains a value returned by `cont`.
      */
    def continueWith[S](cont: Future[T] => S)
                       (implicit executor: ExecutionContext): Future[S] = {
        val p = promise[S]()
        f.onComplete { _ =>
            p complete Try(cont(f))
        }(CallingThreadExecutionContext)
        p.future
    }

     /* Continues the computation of this future by taking the result
      *  of the current future and mapping it into another future.
      *
      *  The function `cont` is called only after the current future completes.
      *  The resulting future contains a value returned by `cont`.
      */
    def continue[S](cont: Try[T] => S)
                   (implicit executor: ExecutionContext): Future[S] = {
        val p = promise[S]()
        f.onComplete { x =>
            p complete Try(cont(x))
        }(CallingThreadExecutionContext)
        p.future
    }
}
