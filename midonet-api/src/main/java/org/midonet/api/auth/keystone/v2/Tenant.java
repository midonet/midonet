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

package org.midonet.api.auth.keystone.v2;

import org.codehaus.jackson.annotate.JsonProperty;

import org.midonet.api.auth.keystone.Project;

public class Tenant implements Project {

    @JsonProperty("id")
    public String id;
    @JsonProperty("enabled")
    public Boolean enabled;
    @JsonProperty("name")
    public String name;
    @JsonProperty("description")
    public String description;

    public Tenant() { }

    public Tenant(String id,
                  Boolean enabled,
                  String name,
                  String description) {
        this.id = id;
        this.enabled = enabled;
        this.name = name;
        this.description = description;
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getName() { return name; }

    @Override
    public Boolean getEnabled() { return enabled; }

}
