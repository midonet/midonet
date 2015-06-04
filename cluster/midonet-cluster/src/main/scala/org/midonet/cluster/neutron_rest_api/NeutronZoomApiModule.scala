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

package org.midonet.cluster.neutron_rest_api

import com.google.inject.PrivateModule

import org.midonet.cluster.data.neutron.NeutronClusterModule

/** Injects an implementation of the Neutron Backend API that implements
  * all the methods required by the Neutron REST API based on a ZOOM storage.
  */
class NeutronZoomApiModule extends PrivateModule {

    override protected def configure() {
        binder.requireExplicitBindings()

        install(new NeutronClusterModule)
        bind(classOf[NetworkApi])
            .to(classOf[NeutronZoomPlugin])
        bind(classOf[L3Api])
            .to(classOf[NeutronZoomPlugin])
        bind(classOf[SecurityGroupApi])
            .to(classOf[NeutronZoomPlugin])
        bind(classOf[LoadBalancerApi])
            .to(classOf[NeutronZoomPlugin])
        expose(classOf[NetworkApi])
        expose(classOf[L3Api])
        expose(classOf[SecurityGroupApi])
        expose(classOf[LoadBalancerApi])
    }
}