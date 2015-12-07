package org.midonet.cluster.services.containers.schedulers

import java.util
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.reflect.ClassTag

import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Subscription}

import org.midonet.cluster.data.storage.{StateKey, StateStorage}
import org.midonet.util.functors._

/** Tracks the liveness of objects of the given type, defined by the
  * existence or not of the given key.o
  *
  * Example:
  *
  *     new LivenessTracker[Topology.Host](stateStore, MidonetBackend.AliveKey)
  */
class LivenessTracker[D](stateStore: StateStorage, key: String)
                        (implicit ct: ClassTag[D]) {

    private val objectClass = ct.runtimeClass

    private val interestSet = new ConcurrentHashMap[UUID, Subscription]
    private val liveSubject = PublishSubject.create[UUID]
    private val deadSubject = PublishSubject.create[UUID]
    private val subscription = new CompositeSubscription()

    private val liveHosts = new util.HashSet[UUID]

    /** Tells whether a host is alive or not.
      *
      * @return true for live hosts, false for dead or unknown hosts.
      */
    def isAlive(hostId: UUID): Boolean = liveHosts contains hostId

    /** Start watching the given list of hosts.
      */
    def watch(ids: util.List[UUID]): Unit = {
        val it = ids.iterator()
        while (it.hasNext)
            doWatchHost(it.next())
    }

    private def doWatchHost(id: UUID): Unit = {
        if (interestSet.contains(id))
            return
        val s = stateStore.keyObservable(id.toString, objectClass, id, key)
                          .subscribe(makeAction1[StateKey] { k =>
                              if (k.isEmpty)
                                  deadSubject.onNext(id)
                              else
                                 liveSubject.onNext(id)
                          })
        interestSet.put(id, s)
        subscription.add(s)
    }

    /** An Observable that exposes hosts that become live.
      */
    def aliveObservable: Observable[UUID] = liveSubject.asObservable()

    /** An Observable that exposes the hosts that become not alive.
      */
    def deadObservable: Observable[UUID] = deadSubject.asObservable()

}
