package org.midonet.midolman.state

import rx.Observable.OnSubscribe
import rx.Subscriber

import org.midonet.midolman.state.MapObservableOnSubscribe.log
import org.midonet.midolman.state.ReplicatedMap.Watcher

case class MapNotification[K, V](key: K, oldVal: V,  newVal: V)

object MapObservableOnSubscribe {
    import org.slf4j.LoggerFactory.getLogger
    val log = getLogger("org.midonet.cluster.state")
}

/** Turns a ReplicatedMap into an Observable. The ReplicatedMap will be
  * subscribed to as soon as the first subscriber comes in.
  *
  * Note that the first subscriber will receive a first set of entries as the
  * map is primed.  Later subscribers will only receive updates from the
  * instant they subscribe.
  */
class MapObservableOnSubscribe[K, V](rm: ReplicatedMap[K, V])
    extends OnSubscribe[MapNotification[K, V]] {

    override def call(s: Subscriber[_ >: MapNotification[K, V]]): Unit = {
        rm.addWatcher(new Watcher[K, V] {
            override def processChange(key: K, oldVal: V, newVal: V): Unit = {
                s.onNext(new MapNotification(key, oldVal, newVal))
            }
        })
        try {
            rm.start()
        } catch {
            case t: Throwable => 
                log.warn("Failed to load Replicated Map", t)
        }
    }
}
