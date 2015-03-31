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
package org.midonet.brain.services.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;

@XmlRootElement(name = "router")
@ZoomClass(clazz = Topology.Router.class)
public class Router extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID inboundFilterId;
    @ZoomField(name = "outbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID outboundFilterId;

    @ZoomField(name = "load_balancer_id", converter = UUIDUtil.Converter.class)
    public UUID loadBalancerId;

    public Router() {
        adminStateUp = true;
    }

    @Override
    public void setBaseUri(URI baseUri) {
        super.setBaseUri(baseUri);
    }

    @Override
    public String getUri() {
        return ResourceUris.ROUTERS;
    }
}
