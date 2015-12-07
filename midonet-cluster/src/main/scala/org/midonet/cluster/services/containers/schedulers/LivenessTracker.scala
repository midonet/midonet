package org.midonet.cluster.services.containers.schedulers

import java.util
import java.util.UUID

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory.getLogger
import rx.subjects.PublishSubject
import rx.{Observable, Observer}

import org.midonet.cluster.data.storage.{StateKey, StateStorage}

sealed trait Liveness
case class Alive(id: UUID) extends Liveness
case class Dead(id: UUID) extends Liveness

/** Tracks the liveness of objects of the given type, defined by the
  * existence or not of the given key.o
  *
  * Example:
  *
  *   val tracker = new LivenessTracker[Topology.Host](stateStore,
  *                                                    MidonetBackend.AliveKey)
  *
  *   tracker.watch(ids)
  */
class LivenessTracker[D](stateStore: StateStorage, key: String)
                        (implicit ct: ClassTag[D], ec: ExecutionContext) {

    private val objectClass = ct.runtimeClass
    private val objectClassName = ct.runtimeClass.getSimpleName
    private val log = Logger(getLogger(
        s"org.midonet.cluster.containers.liveness-tracker-$objectClassName-$key"))

    /** Contains an index of entity id -> a future that completes when the
      * initial state is first loaded.
      */
    private val interestSet = new util.HashSet[UUID]
    private val subject = PublishSubject.create[Liveness]

    private val liveHosts = new util.HashSet[UUID]

    /** Tells whether a host is alive or not.
      *
      * @return true for live entities, false for dead or unknown ones.
      */
    def isAlive(hostId: UUID): Boolean = liveHosts contains hostId

    /** Start watching the given list of entities if they are not already
      * watched.  The returned [[Future]] will complete when the full list
      * of ids has been loaded.
      *
      * This method is NOT thread safe and will misbehave if called
      * concurrently from different threads.
      */
    def watch(ids: Set[UUID]): Future[Set[UUID]] = {
        Future.sequence(ids map doWatchHost)
    }

    /** Ensure that we're watching the given element, and return a [[Future]]
      * that will complete when the initial state is loaded (or immediately
      * if the value is already known).
      */
    private def doWatchHost(id: UUID): Future[UUID] = {

        if (!interestSet.add(id))
            return Promise[UUID].success(id).future

        val hostStatePrimed = Promise[UUID]()
        val observer = new KeyObserver(id, hostStatePrimed)
        stateStore.keyObservable(id.toString, objectClass, id, key)
                  .subscribe(observer)
        hostStatePrimed.future
    }

    private class KeyObserver(id: UUID, p: Promise[UUID])
        extends Observer[StateKey] {
        override def onCompleted(): Unit = {
        }
        override def onError(e: Throwable): Unit = {
            log.warn(s"Failure in state key for $objectClass.getSimpleName " +
                     s"with ID: $id")
        }
        override def onNext(k: StateKey): Unit = {
            val isAlive = k.nonEmpty
            p.trySuccess(id)
            if (isAlive)
                declareAlive(id)
            else
                declareDead(id)
        }
    }

    @inline def declareAlive(id: UUID): Unit = {
        log.debug(s"Declare host $id alive")
        liveHosts.add(id)
        subject onNext Alive(id)
    }

    @inline def declareDead(id: UUID): Unit = {
        log.debug(s"Declare host $id not alive")
        liveHosts.remove(id)
        subject onNext Dead(id)
    }

    /** An Observable that exposes entities that become alive/dead.
      */
    def observable: Observable[Liveness] = subject.asObservable()


}
