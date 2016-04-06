
/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.containers


import com.google.inject.Inject
import org.midonet.cluster.services.MidonetBackend
import org.midonet.containers.{Container, Containers}

object QuaggaContainerDelegate {
    final val MaxStorageAttempts = 10
}

@Container(name = Containers.QUAGGA_CONTAINER, version = 1)
class QuaggaContainerDelegate @Inject()(backend: MidonetBackend)
    extends DatapathBoundContainerDelegate(backend) {
    override val name = "bgp"
}
