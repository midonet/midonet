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

package org.midonet.cluster.rest_api.conversion;

import java.net.URI;

import org.midonet.cluster.rest_api.models.PortGroup;

import static org.midonet.cluster.data.PortGroup.Property.*;

public class PortGroupDataConverter {

    public static PortGroup fromData(org.midonet.cluster.data.PortGroup data,
                                     URI baseUri) throws
                                                  IllegalAccessException {
        PortGroup pg = new PortGroup();
        pg.id = data.getId();
        pg.name = data.getName();
        pg.tenantId = data.getProperty(tenant_id);
        pg.stateful = data.isStateful();
        pg.setBaseUri(baseUri);
        return pg;
    }

    public static org.midonet.cluster.data.PortGroup toData(PortGroup pg) {
        return new org.midonet.cluster.data.PortGroup()
            .setId(pg.id)
            .setName(pg.name)
            .setProperty(tenant_id, pg.tenantId)
            .setStateful(pg.stateful);
    }

}


