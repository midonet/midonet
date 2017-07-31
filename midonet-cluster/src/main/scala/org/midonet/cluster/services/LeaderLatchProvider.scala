package org.midonet.cluster.services

import java.util.concurrent.ConcurrentHashMap

import com.google.common.annotations.VisibleForTesting
import com.google.inject.{Inject, Provider}

import org.apache.curator.framework.recipes.leader.LeaderLatch

import org.midonet.cluster.conf.ClusterConfig
import org.midonet.minion.Minion

/** A simple factory of [[org.apache.curator.framework.recipes.leader.LeaderLatch]]
  * that can be used by [[Minion]] instances, while allowing easy mocking.
  */
class LeaderLatchProvider @Inject()(backend: MidonetBackend,
                                    config: ClusterConfig)
    extends Provider[LeaderLatch] {

    private val latches = new ConcurrentHashMap[String, LeaderLatch]

    override def get(): LeaderLatch = {
        throw new IllegalArgumentException("Use get(name)")
    }

    /** @param path relative to the config.backend.rootKey (e.g.: /mylatch"
      */
    final def get(path: String) = {
        latches.get(path) match {
            case null =>
                val latch = buildLatch(path)
                latches.put(path, latch)
                latch
            case latch => latch
        }
    }

    @VisibleForTesting
    def buildLatch(path: String): LeaderLatch = {
        new LeaderLatch(backend.curator,
                        s"${config.backend.rootKey}$path") {

        }
    }
}
