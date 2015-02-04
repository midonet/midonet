/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.state

import org.apache.zookeeper.KeeperException

import org.midonet.cluster.backend.zookeeper.StateAccessException

object ZKExceptions {

    /** Wrap a block of code with lazy "call by name" semantics in a
     *  try catch block which adapts ZK exceptions to Midolman exceptions.
     *
     *  @param  block of code which can potentially throw KeeperException or
     *          InterruptedException.
     *  @return the return value of the block of code passed as argument.
     */
    def adapt[T](f: => T): T = try { f } catch {
        case e: KeeperException      => throw new StateAccessException(e)
        case e: InterruptedException => throw new StateAccessException(e)
    }

}
