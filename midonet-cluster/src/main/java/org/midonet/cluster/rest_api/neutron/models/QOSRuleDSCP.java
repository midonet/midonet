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
import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

@ZoomClass(clazz = Neutron.NeutronQOSRuleBWLimit.class)
public class QOSRuleDSCP extends ZoomObject {
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("dscp_mark")
    @ZoomField(name = "dscp_mark")
    public long  dscpMark;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        QOSRuleDSCP that = (QOSRuleDSCP) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(dscpMark, that.dscpMark);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, dscpMark);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("dscpMark", dscpMark)
            .toString();
    }
}
