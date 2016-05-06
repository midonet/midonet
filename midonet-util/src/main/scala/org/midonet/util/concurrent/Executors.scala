package org.midonet.util.concurrent

import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import java.util.concurrent._

/**
  * Utility class for creating custom executors.
  */
object Executors {

    val CallerRunsPolicy = new CallerRunsPolicy()

    /**
      * @return A single-threaded scheduled executor.
      */
    def singleThreadScheduledExecutor(name: String, isDeamon: Boolean,
                                      handler: RejectedExecutionHandler)
    : ScheduledExecutorService = {
        new ScheduledThreadPoolExecutor(
            1, new ThreadFactory {
                override def newThread(r: Runnable): Thread = {
                    val thread = new Thread(name)
                    thread.setDaemon(isDeamon)
                    thread
                }
            }, handler)
    }

    /**
      * @return An unbounded cached executor pool.
      */
    def cachedPoolExecutor(name: String, isDeamon: Boolean,
                           handler: RejectedExecutionHandler)
    : ExecutorService = {
        new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue[Runnable](),
            new NamedThreadFactory(name, isDaemon = isDeamon),
            handler)
    }

}
