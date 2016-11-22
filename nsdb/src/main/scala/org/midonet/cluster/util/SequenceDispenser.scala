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

import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.google.inject.Inject

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.shared.{SharedCount, VersionedValue}

import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.SequenceDispenser.SequenceType

object SequenceDispenser {
    abstract class SequenceType(val tag: String, val seed: Int)

    case object OverlayTunnelKey extends SequenceType("OVERLAY_TUNNEL_KEY", 1)
    case object VxgwVni extends SequenceType("VXGW_TUNNEL_KEY", 10000)
    case object Fip64TunnelKey extends SequenceType("FIP64_TUNNEL_KEY", 1)

    val Sequences = Seq (
        OverlayTunnelKey,
        VxgwVni
    )
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

    class SequenceException(t: SequenceType)
        extends Exception("Can't write counter for type " + t)

    private implicit val ec = fromExecutor(directExecutor())

    // This gets added to the root path of the Curator instance.

    private val root = backendCfg.rootKey + "/sequences"

    private def pathFor(which: SequenceType) =
        s"$root/${which.tag.toLowerCase}"

    /** Retrieve the current value at the counter.  */
    def current(which: SequenceType): Future[Int] = Future {
        val counter = new SharedCount(curator, pathFor(which), which.seed)
        counter.start()
        val count = counter.getCount
        counter.close()
        count
    }

    /** Reserve and retrieve the next value on the counter, retrying for a
      * limited number of times if contention is found on the counter.  */
    def next(which: SequenceType): Future[Int] = Future {
        val counter = new SharedCount(curator, pathFor(which), which.seed)
        counter.start()
        var retries = 10
        var cur: VersionedValue[Integer] = null
        do {
            if (retries == 0) {
                throw new SequenceException(which)
            }
            retries = retries - 1
            cur = counter.getVersionedValue
        } while (!counter.trySetCount(cur, cur.getValue + 1))
        counter.close()
        cur.getValue + 1
    }

}

