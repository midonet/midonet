package org.midonet.midolman.state

import rx.Observable.OnSubscribe
import rx.Subscriber

import org.midonet.midolman.state.ReplicatedMap.Watcher

case class MapNotification[K, V](key: K, oldVal: V,  newVal: V)

/** Turns a ReplicatedMap into an Observable. The ReplicatedMap will be
  * subscribed to as soon as the first subscriber comes in. */
class MapObservableOnSubscribe[K, V](rm: ReplicatedMap[K, V])
    extends OnSubscribe[MapNotification[K, V]] {

    override def call(s: Subscriber[_ >: MapNotification[K, V]]): Unit = {
        rm.addWatcher(new Watcher[K, V] {
            override def processChange(key: K, oldVal: V, newVal: V): Unit = {
                s.onNext(new MapNotification(key, oldVal, newVal))
            }
        })
        rm.start()
    }
}
