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

package org.midonet.cluster.rest_api.neutron.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.NeutronRouter.ExternalGatewayInfo.class)
public class LBV2SessionPersistenceAlgorithm extends ZoomObject {

    public LBV2SessionPersistenceAlgorithm() {}

    public LBV2SessionPersistenceAlgorithm(String type, String cookieName) {
        this.type = type;
        this.cookieName = cookieName;
    }

    @ZoomField(name = "type")
    public String type;

    @JsonProperty("cookie_name")
    @ZoomField(name = "cookie_name")
    public String cookieName;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("type", type)
                .add("cookieName", cookieName)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LBV2SessionPersistenceAlgorithm that = (LBV2SessionPersistenceAlgorithm) o;
        return Objects.equal(type, that.type) &&
                Objects.equal(cookieName, that.cookieName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(type, cookieName);
    }
}
