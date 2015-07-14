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

package org.midonet.cluster.util

import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.Future

import com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.shared.SharedCount

import org.midonet.cluster.storage.MidonetBackendConfig

object SequenceType extends Enumeration {
    val OverlayTunnelKey = Value("OVERLAY_TUNNEL_KEY")
    val VxgwVni = Value("VXGW_TUNNEL_KEY")
}

/** This class provides a catalogue of several types of counters used in
  * MidoNet for various purposes.  Centralizing it here allows different
  * components to access and mutate them in a reliable way using common
  * mechanisms and a consistent layout in the backend storage.
  *
  * TODO: I don't like passing the midonetBackendCfg so that it looks the root
  * in config and composes the path.  Instead, this class should be getting a
  * namespaced Curator instance, and just use relative paths.  Unfortunately
  * this requires more changes in all our bootstrapping than we can afford now.
  *
  * @param curator the curator instance to use.
  */
class SequenceDispenser @Inject()(curator: CuratorFramework,
                                  backendCfg: MidonetBackendConfig){

    class SequenceReadException(t: SequenceType.Value,
                                 cause: Throwable = null)
        extends Exception("Can't read counter for type " + t, cause)

    private implicit val ec = fromExecutor(sameThreadExecutor())

    // This gets added to the root path of the Curator instance.

    private val paths = Map (
        SequenceType.OverlayTunnelKey -> "agent_tunnel_keys",
        SequenceType.VxgwVni -> "vxgw_vni"
    )

    private val root = backendCfg.rootKey + "/sequences/"

    /** Retrieve the current value at the counter.  */
    def current(which: SequenceType.Value): Future[Int] = Future {
        paths.get(which).map { p =>
            val counter = new SharedCount(curator, root + p, 0)
            counter.start()
            val count = counter.getCount
            counter.close()
            count
        } getOrElse {
            throw new SequenceReadException(which)
        }
    }

    /** Reserve and retrieve the next value on the counter.  */
    def next(which: SequenceType.Value): Future[Option[Int]] = Future {
        paths.get(which) flatMap { p =>
            val counter = new SharedCount(curator, root + p, 0)
            counter.start()
            val curr = counter.getVersionedValue
            val next = curr.getValue + 1
            val real = if (counter.trySetCount(curr, next)) Some(next) else None
            counter.close()
            real
        }
    }

}

