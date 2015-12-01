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

package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.validation.MessageProperty;

@ZoomClass(clazz = Topology.LoadBalancer.class)
public class LoadBalancer extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "router_id")
    public UUID routerId;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp = true;

    @JsonIgnore
    @ZoomField(name = "pool_ids")
    public List<UUID> poolIds;

    public URI getUri() {
        return absoluteUri(ResourceUris.LOAD_BALANCERS(), id);
    }

    public URI getRouter() {
        return absoluteUri(ResourceUris.ROUTERS(), routerId);
    }

    public URI getPools() {
        return relativeUri(ResourceUris.POOLS());
    }

    public URI getVips() {
        return relativeUri(ResourceUris.VIPS());
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
        if (null != routerId) {
            throw new BadRequestHttpException(
                MessageProperty.getMessage(
                    MessageProperty.ROUTER_ID_IS_INVALID_IN_LB));
        }
    }

    @JsonIgnore
    public void update(LoadBalancer from) {
        id = from.id;
        poolIds = from.poolIds;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("routerId", routerId)
            .add("adminStateUp", adminStateUp)
            .add("poolIds", poolIds)
            .toString();
    }
}
