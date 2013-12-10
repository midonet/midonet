/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util

import scala.concurrent.{ExecutionContext, Future}

package object concurrent {

    /* Note that FutureOps is a value class. No allocation will actually result
     * from the new() operation. A call such as f.continue(cont) will undergo a
     * compile-time transformation into a call on a static object, for example
     * FutureOps.MODULE$.extension$continue(f, cont).
     */
    implicit def toFutureOps[T](f: Future[T]): FutureOps[T] = new FutureOps(f)

    implicit def toCompanionFutureOps(f: Future.type) = new FutureCompanionOps(f)

    implicit class ExecutionContextOps(val ec: ExecutionContext.type)
            extends AnyVal {
        def callingThread = CallingThreadExecutionContext
    }
}
