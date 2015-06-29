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

@ZoomClass(clazz = Neutron.NeutronPort.ExtraDhcpOpts.class)
public class ExtraDhcpOpt extends ZoomObject {

    public ExtraDhcpOpt() {}

    public ExtraDhcpOpt(String optName, String optValue) {
        this.optName = optName;
        this.optValue = optValue;
    }

    @JsonProperty("opt_name")
    @ZoomField(name = "opt_name")
    public String optName;

    @JsonProperty("opt_value")
    @ZoomField(name = "opt_value")
    public String optValue;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof ExtraDhcpOpt)) return false;

        final ExtraDhcpOpt other = (ExtraDhcpOpt) obj;

        return Objects.equal(optName, other.optName)
                && Objects.equal(optValue, other.optValue);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(optName, optValue);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("optName", optName)
                .add("optValue", optValue).toString();
    }
}
