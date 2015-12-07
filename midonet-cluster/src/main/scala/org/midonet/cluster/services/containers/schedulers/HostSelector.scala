package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory.getLogger
import rx.Observable

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.ServiceContainerGroup
import org.midonet.cluster.util.logging.ProtoTextPrettifier.makeReadable

/**
  * A HostSelector trait that exposes the interface to be implemented by
  * specific allocation policies. It exposes: 1) a discardedHosts:[[rx.Observable]]
  * that notifies of hosts that are now being discarded as eligible; 2) a
  * candidateHosts:[[Future]] that returns (on completion) a [[Set]] of [[UUID]]
  * containing the current set of eligible hosts. With this information, the
  * scheduler will take the corresponding scheduling decisions.
  *
  * Implementations of the HostSelector trait should implement the
  * `onServiceContainerGroupUpdate` method if any action is required on the
  * host selector side when the associated service container group is updated.
  */
trait HostSelector {

    /** Observable that emits events as soon as a host is no longer eligible.
      */
    val discardedHosts: Observable[UUID]

    /** Current set of candidate hosts.
      */
    def candidateHosts: Future[Set[UUID]]

    /** Use when the Selector is not needed anymore */
    def dispose(): Unit

}

/** Builds the right type of HostSelector based on a ServiceContainerGroup. */
class HostSelectorFactory @Inject()(store: Storage, scheduler: rx.Scheduler)
                                   (implicit ec: ExecutionContext) {

    protected val log = Logger(getLogger("org.midonet.cluster.containers"))

    lazy val anywhereHostSelector = new AnywhereHostSelector(store, scheduler)

    def getHostSelector(group: ServiceContainerGroup): HostSelector = {
        if (!group.hasHostGroupId && !group.hasPortGroupId) {
            return anywhereHostSelector
        }
        val scgId = makeReadable(group.getId)
        throw new IllegalArgumentException("Unsupported allocation policy for" +
                                           s" service container group $scgId")
    }
}
