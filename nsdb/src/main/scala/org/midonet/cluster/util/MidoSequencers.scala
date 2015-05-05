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

import scala.concurrent.{ExecutionContext, Future}

import com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.shared.SharedCount

import org.midonet.cluster.storage.MidonetBackendConfig

object MidoSequenceType extends Enumeration {
    val AGENT_TUNNEL_KEYS = Value("AGENT_TUNNEL_KEYS")
    val VXGW_VNI = Value("VXGW_VNI")
}

/** This class provides a catalogue of several types of counters used in
  * MidoNet for various purposes.  Centralizing ijkt here allows different
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
class MidoSequencers @Inject()(curator: CuratorFramework,
                               backendCfg: MidonetBackendConfig){

    class SequenceReadException(t: MidoSequenceType.Value,
                                 cause: Throwable = null)
        extends Exception("Can't read counter for type " + t, cause)

    class SequenceWriteException(t: MidoSequenceType.Value,
                                  cause: Throwable = null)
        extends Exception("Can't update counter for type " + t, cause)

    private implicit val ec = ExecutionContext.fromExecutor(sameThreadExecutor())

    // This gets added to the root path of the Curator instance.

    val paths = Map (
        MidoSequenceType.AGENT_TUNNEL_KEYS -> "agent_tunnel_keys",
        MidoSequenceType.VXGW_VNI -> "vxgw_vni"
    )

    /** Retrieve the current value at the counter.
      *
      * @throws SequenceReadException when the value can't be retrieved
      */
    @throws[SequenceReadException]
    def current(which: MidoSequenceType.Value): Future[Int] = Future {
        paths.get(which).map { p =>
            val path = s"${backendCfg.rootKey}/$p"
            val counter = new SharedCount(curator, path, 0)
            counter.start()
            val count = counter.getCount
            counter.close()
            count
        } getOrElse {
            throw new SequenceReadException(which)
        }
    }

    /** Reserve and retrieve the next value on the counter.
      *
      * @throws SequenceWriteException when the value can't be retrieved
      * @throws SequenceReadException when the value can't be retrieved
      */
    def next(which: MidoSequenceType.Value): Future[Option[Int]] = Future {
        paths.get(which) flatMap { p =>
            val path = s"${backendCfg.rootKey}/$p"
            val counter = new SharedCount(curator, path, 0)
            counter.start()
            val curr = counter.getVersionedValue
            val next = curr.getValue + 1
            val real = if (counter.trySetCount(curr, next)) Some(next) else None
            counter.close()
            real
        }
    }

}

