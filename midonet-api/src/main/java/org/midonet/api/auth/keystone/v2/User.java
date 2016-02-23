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

import java.util.List;

import org.codehaus.jackson.annotate.JsonProperty;

public class User implements org.midonet.api.auth.keystone.User {

    @JsonProperty("id")
    public String id;
    @JsonProperty("username")
    public String name;
    @JsonProperty("name")
    public String actualName;
    @JsonProperty("enabled")
    public Boolean enabled;
    @JsonProperty("email")
    public String email;
    @JsonProperty("roles")
    public List<Role> roles;
    @JsonProperty("role_links")
    public List<String> roleLinks;

    public User() { }

    public User(String id,
                String name,
                String actualName,
                Boolean enabled,
                String email,
                List<Role> roles,
                List<String> roleLinks) {
        this.id = id;
        this.name = name;
        this.actualName = actualName;
        this.enabled = enabled;
        this.email = email;
        this.roles = roles;
        this.roleLinks = roleLinks;
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getName() { return name; }

    @Override
    public Boolean getEnabled() { return enabled; }

    @Override
    public String getEmail() { return email; }
    
}
