/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable

/**
 * A monad that very similar to Try, but where the semantics for Failure are
 * not based on a Throwable but a Future. An Urgent represents a
 * computation that will succeed only it's immediately available, or else
 * provide the Future that encapsulates the computation that wasn't ready in
 * time to provide the final result.
 */
sealed trait Urgent[+T] {

    /**
     * @return whether the Urgent has been able to complete.
     */
    def isReady: Boolean

    /**
     * @return the final result
     * @throws UnavailableException when it wasn't possible to compute the value
     */
    def get: T
    def flatMap[U](f: T => Urgent[U]): Urgent[U]
    def map[U](f: T => U): Urgent[U]
}

/**
 * Thrown when trying to access a value contained in an Urgent that is not
 * ready.
 * @param ft the future that prevented the computation from being completed
 */
final class UnavailableException[T](ft: Future[T]) extends Exception

object Urgent {

    def apply[T](result: T): Urgent[T] = Ready(result)

    /**
     * Gives you the Urgent[Seq[T]] with either the final sequence
     * containing all the computed T's in the given sequence if they are all
     * Ready, or a NotYet with the first future.
     */
    def flatten[T](results: Seq[Urgent[T]])(implicit ec: ExecutionContext)
    : Urgent[Seq[T]] = {
        val pending = mutable.ListBuffer.empty[Future[T]]
        val ready = mutable.ListBuffer.empty[T]
        val it = results.iterator
        while (it.hasNext) {
            it next() match {
                case Ready(t) => ready.append(t)
                case n@NotYet(ft) => pending.append(n.ft)
            }
        }
        if (pending.isEmpty) Ready(ready)
        else NotYet(Future.sequence(pending))
    }
    def ready[T](result: T): Urgent[T]  = Ready(result)
    def unavailable[T](future: Future[T]): Urgent[T]  = NotYet(future)
}

/**
 * All the resources required to perform the computation were ready so the
 * result t was produced
 * @param t the result of the computation
 */
final case class Ready[+T](t: T) extends Urgent[T] {
    override def isReady = true
    override def get = t
    override def flatMap[U](f: T => Urgent[U]): Urgent[U] = f(t)
    override def map[U](f: T => U): Urgent[U] = Urgent.ready(f(t))
}

/**
 * A resource required to perform the computation was not immediately available
 * @param ft the Future with the missing resource
 */
final case class NotYet[+T](ft: Future[T]) extends Urgent[T] {
    override def isReady = false
    override def get = throw new UnavailableException(ft)
    override def flatMap[U](f: T => Urgent[U]): Urgent[U] = this.asInstanceOf[Urgent[U]]
    override def map[U](f: T => U): Urgent[U] = this.asInstanceOf[NotYet[U]]
}

object UrgentConversions {
    def urgentToFuture[T](u: Urgent[T]): Future[T] = {
        u match {
            case Ready(r) => Future.successful(r)
            case NotYet(f) => f
        }
    }
}
