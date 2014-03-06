/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.mutable
import scala.concurrent.duration.Duration

/**
 * A monad that is very similar to Try, but whose semantics for Failure are
 * not based on a Throwable but a Future. An Urgent represents a
 * computation that will succeed only if the resources required to perform it
 * are immediately available, or else provide the Future that encapsulates the
 * computation of the resource that had not been completed in time for the
 * Urgent to reach the final result.
 */
sealed trait Urgent[+T] {

    /**
     * @return whether the Urgent has been able to complete.
     */
    def isReady: Boolean
    def notReady: Boolean = !isReady

    /**
     * @return the final result.
     * @throws UnavailableException when the value could not be computed due to
     *                              resources not being available in time.
     */
    def get: T
    def flatMap[U](f: T => Urgent[U]): Urgent[U]
    def map[U](f: T => U): Urgent[U]

    def ifReady(f: T => Unit) = {
        if (isReady)
            f(this.get)
        this
    }

    def ifNotReady(f: () => Unit) = {
        if (notReady)
            f()
        this
    }
}

/**
 * Thrown when trying to access a value contained in an Urgent that is not
 * ready.
 * @param ft the future that prevented the computation from being completed
 */
final class UnavailableException[T](ft: Future[_]) extends Exception

object Urgent {

    def apply[T](result: T): Urgent[T] = Ready(result)

    /**
     * Gives you the Urgent[Seq[T]] with either the final sequence
     * containing all the computed T's in the given sequence if they are all
     * Ready, or a NotYet with the first future.
     *
     * Note that the results Seq is expected to NOT contain any nulls.
     */
    def flatten[T](results: Seq[Urgent[T]])(implicit ec: ExecutionContext)
    : Urgent[Seq[T]] = {
        val pending = mutable.ListBuffer.empty[Future[Any]]
        val ready = mutable.ListBuffer.empty[T]
        val it = results.iterator
        while (it.hasNext) {
            it next() match {
                case null => throw new IllegalArgumentException("Null Urgent")
                case Ready(t) => ready.append(t)
                case n@NotYet(ft) => pending.append(ft)
            }
        }

        if (pending.isEmpty)
            Ready(ready)
        else {
            val f: Future[Seq[Any]] = Future.sequence(pending)
            unavailable[Seq[T]](f)
        }
    }

    def ready[T](result: T): Urgent[T]  = Ready(result)
    def unavailable[T](future: Future[_]): Urgent[T]  = NotYet(future)
}

/**
 * All the resources required to perform the computation were ready in time so
 * the final result t was produced.
 *
 * @param t the result of the computation.
 */
final case class Ready[+T](t: T) extends Urgent[T] {
    override def isReady = true
    override def get = t
    override def flatMap[U](f: T => Urgent[U]): Urgent[U] = f(t)
    override def map[U](f: T => U): Urgent[U] = Urgent.ready(f(t))
    override def toString: String = s"Ready[${t.toString}]"
}

/**
 * A resource required to perform the computation of the final result was not
 * available in time.
 *
 * @param ft the Future with the incomplete resource.
 */
final case class NotYet[+T](ft: Future[_]) extends Urgent[T] {
    override def isReady = false
    override def get = throw new UnavailableException(ft)
    override def flatMap[U](f: T => Urgent[U]): Urgent[U] = this.asInstanceOf[Urgent[U]]
    override def map[U](f: T => U): Urgent[U] = this.asInstanceOf[Urgent[U]]
    override def toString: String = "NotYet"
}

