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
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.NeutronPort.BindingProfile.class)
public class PortBindingProfile extends ZoomObject {

    @JsonProperty("interface_name")
    @ZoomField(name = "interface_name")
    public String interfaceName;

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof PortBindingProfile)) return false;
        final PortBindingProfile other = (PortBindingProfile)o;
        return Objects.equal(interfaceName, other.interfaceName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(interfaceName);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("interfaceName", interfaceName).toString();
    }
}
