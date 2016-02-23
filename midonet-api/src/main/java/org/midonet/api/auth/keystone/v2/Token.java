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
import org.codehaus.jackson.map.annotate.JsonSerialize;

@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
public class Token implements org.midonet.api.auth.keystone.Token {

    @JsonProperty("id")
    public String id;
    @JsonProperty("issued_at")
    public String issuedAt;
    @JsonProperty("expires")
    public String expiresAt;
    @JsonProperty("tenant")
    public Tenant tenant;
    @JsonProperty("audit_ids")
    public List<String> auditIds;

    public Token() { }

    public Token(String id,
                 String issuedAt,
                 String expiresAt,
                 Tenant tenant,
                 List<String> auditIds) {
        this.id = id;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.tenant = tenant;
        this.auditIds = auditIds;
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getIssuedAt() { return issuedAt; }

    @Override
    public String getExpiresAt() { return expiresAt; }

}
