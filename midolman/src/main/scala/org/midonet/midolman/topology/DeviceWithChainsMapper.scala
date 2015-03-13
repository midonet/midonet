/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.topology

import java.util.UUID

import javax.annotation.concurrent.NotThreadSafe

import scala.collection.mutable
import scala.reflect.ClassTag

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.topology.DeviceWithChainsMapper.ChainState
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.util.functors.makeAction1

object DeviceWithChainsMapper {

    /**
     * Stores the state for a chain, and exposes an [[Observable]] that emits
     * updates for this chain. The observable completes either when the chain
     * is deleted, or when calling the complete() method, which is used to
     * signal that a chain no longer belongs to the device with chains.
     */
    private class ChainState(val chainId: UUID) {

        private var currentChain: Chain = null
        private val mark = PublishSubject.create[Chain]()

        val observable = VirtualTopology.observable[Chain](chainId)
            .doOnNext(makeAction1(currentChain = _))
            .takeUntil(mark)

        /** Completes the observable corresponding to this chain state. */
        def complete(): Unit = mark.onCompleted()
        /** Get the chain for this chain state. */
        def chain: Chain = currentChain
        /** Indicates whether the chain state has received the chain data. */
        def isReady: Boolean = currentChain ne null
    }

}

/**
 * An abstract implementation for [[DeviceMapper]] that adds support for
 * caching [[Chain]]s. This abstract mapper can be used by the mapper
 * implementation for any device with chains to prefetch the chain devices.
 */
abstract class DeviceWithChainsMapper[D <: VirtualDevice](deviceId: UUID,
                                                          vt: VirtualTopology)
                                                         (implicit tag: ClassTag[D])
    extends VirtualDeviceMapper[D](deviceId, vt)(tag) {

    private val chainsSubject = PublishSubject.create[Observable[Chain]]
    private val chains = new mutable.HashMap[UUID, ChainState]

    /**
     * Requests the set of chains for the current device. The argument must
     * specify all the chains used by the current device, where all chain
     * identifiers must be different from null. If a previously requested chain
     * is not found in the given set, then the method unsubscribes from that
     * chain. This method must only be called from the VT thread.
     */
    @NotThreadSafe
    protected final def requestChains(chainIds: Set[UUID]): Unit = {
        assertThread()

        log.debug("Updating chains: {}", chainIds)

        // Remove the chains that are no longer used.
        for ((chainId, chainState) <- chains if !chainIds.contains(chainId)) {
            chainState.complete()
            chains -= chainId
        }
        // Create the state and emit observables for the new chains.
        for (chainId <- chainIds if !chains.contains(chainId)) {
            val chainState = new ChainState(chainId)
            chains += chainId -> chainState
            chainsSubject onNext chainState.observable
        }
    }

    /**
     * This method has the same purpose as requestChains(chainIds: Set[UUID]),
     * except that this method takes a variable list of chain identifiers. Nulls
     * are allowed.
     */
    @NotThreadSafe
    protected final def requestChains(chainIds: UUID*): Unit = {
        requestChains(chainIds.filter(_ ne null).toSet)
    }

    /**
     * Completes the chains observable and the observable for all chains that
     * were previously emitted and not completed. This method must only be
     * called for the VT thread.
     */
    @NotThreadSafe
    protected final def completeChains(): Unit = {
        assertThread()
        for (chainState <- chains.values) {
            chainState.complete()
        }
        chainsSubject.onCompleted()
    }

    /**
     * Indicates whether all chains for the device were received.
     */
    @NotThreadSafe
    protected final def areChainsReady: Boolean = {
        assertThread()
        val ready = chains.forall(_._2.isReady)
        log.debug("Chains ready: {}", Boolean.box(ready))
        ready
    }

    /**
     * Returns the last requested chains as an immutable map of chain
     * identifiers to chain simulation objects. If the chain for a certain
     * identifier was not yet received from the virtual topology, its
     * corresponding value in the map is null.
     */
    @NotThreadSafe
    protected final def currentChains: Map[UUID, Chain] = {
        chains.map(e  => (e._1, e._2.chain)).toMap
    }

    /**
     * An observable that emits notifications for the chains.
     */
    protected final val chainsObservable: Observable[Chain] =
        Observable.merge(chainsSubject)

}
