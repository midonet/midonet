package org.midonet.midolman.state

import rx.Observable.OnSubscribe
import rx.Subscriber

import org.midonet.midolman.state.ReplicatedMap.Watcher

case class RMNotification[K, V](key: K, oldVal: V,  newVal: V)

/** Turns a ReplicatedMap into an Observable. The ReplicatedMap will be
  * subscribed to as soon as the first subscriber comes in. */
class RMObservableOnSubscribe[K, V](rm: ReplicatedMap[K, V]) extends
                                        OnSubscribe[RMNotification[K, V]] {

    override def call(s: Subscriber[_ >: RMNotification[K, V]]): Unit = {
        rm.addWatcher(new Watcher[K, V] {
            override def processChange(key: K, oldVal: V, newVal: V): Unit = {
                s.onNext(new RMNotification(key, oldVal, newVal))
            }
        })
        rm.start()
    }
}
