/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.util.concurrent

import scala.concurrent.ExecutionContext
import java.util.concurrent.ForkJoinWorkerThread

/* TL;DR: Use this when executing small-ish future continuations.
 *
 * When using the default ExecutionContext, the underlying ExecutorService is a
 * ForkJoinPool. In this thread pool, every thread has an associated Work Stealing
 * Queue (WSQ). A thread enqueues work items at the head of its WSQ and consumes
 * from it in LIFO order, both for data locality and based on the observation that
 * more granular work is enqueued first (think of divide and conquer algorithms).
 * This requires minimum synchronization, as a WSQ is typically backed by an array
 * and two pointers into it (head and tail) and the head is only written to by the
 * local thread. Other threads in the pool steal items from the tail of the WSQ,
 * when there is no work in their own WSQs or in the global queue (inserted into
 * by other threads not belonging to this pool). Ideally, these threads steal work
 * items which reference cold data and are granular in the sense that they will
 * produce work items that will feed the stealing thread's WSQ. Because there can
 * be idle threads in the pool when a thread enqueues into its WSQ, it needs to
 * notify the pool that there is work available and potentially unpark threads.
 *
 * In the context of the scala futures, when a pool thread is completing a future,
 * it will enqueue all its continuations in the specified ExecutionContext (EC).
 * It might enqueue it in the global queue or in it's own WSQ (see
 * https://github.com/scala/scala/blob/e230409c13de167b0f4010464c74328ff91d9043/src/library/scala/concurrent/impl/ExecutionContextImpl.scala#L99)
 * So, we should strive to execute very small future continuations (those that
 * kick off another async call or just log an error) in the context of the thread
 * that is concluding the future by using this EC, so that:
 *
 * 1) If the thread will end up executing the continuation because it goes in its
 * WSQ and it isn't stolen, then we save a round trip to the thread pool, which is
 * probably more expensive than the actual code.
 *
 * 2) The thread may have to unpark another, if there are idle threads, which,
 * regardless if it actually steals the item or not, will probably go right back
 * to sleep and we just incurred in a couple of superfluous context switches and
 * kernel transitions.
 *
 * 3) A thread without work in its WSQ steals these tasks instead of more meaty
 * ones and soon has to go steal more, which is an expensive operation.
 */
object CallingThreadExecutionContext extends ExecutionContext {

    /* We have to be careful with reentrancy, because a runnable may schedule
     * continuations on a future that is already completed and will end up being
     * executed inline. As we don't want to go into stack probing and platform-
     * dependent code, we just use a dumb counter and redirect to another
     * ExecutionContext when it is exceeded.
     */
    val maxReentrancyAllowed = 50
    val reentrancyCounter = new ThreadLocal[Integer] {
        override def initialValue: Integer = maxReentrancyAllowed
    }

    def execute(runnable: Runnable) = {
        val rc = reentrancyCounter.get()
        if (rc > 0) {
            reentrancyCounter.set(rc - 1)
            try {
                runnable.run()
            } finally {
                reentrancyCounter.set(rc)
            }
        } else {
            (Thread.currentThread match {
                case fjw: ForkJoinWorkerThread => fjw.getPool
                case _ => ExecutionContext.global
            }) execute runnable
        }
    }

    def reportFailure(t: Throwable): Unit = {
        ExecutionContext.defaultReporter(t)
    }
}
