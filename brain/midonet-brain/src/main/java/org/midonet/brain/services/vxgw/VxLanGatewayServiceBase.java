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

package org.midonet.brain.services.vxgw;

import org.midonet.brain.ClusterMinion;
import org.midonet.brain.ClusterNode;
import org.midonet.brain.MinionConfig;
import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

/** Just to allow dual implementation with the HA one. */
public abstract class VxLanGatewayServiceBase extends ClusterMinion {

    protected VxLanGatewayServiceBase(ClusterNode.Context nodeContext) {
        super(nodeContext);
    }

}

