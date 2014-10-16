/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoRule;
import org.midonet.client.dto.DtoRuleChain;

public class RuleChain extends ResourceBase<RuleChain, DtoRuleChain> {

    public RuleChain(WebResource resource, URI uriForCreation, DtoRuleChain c) {
        super(resource, uriForCreation, c,
              VendorMediaType.APPLICATION_CHAIN_JSON);
    }

    /**
     * Get URI for this rule chain.
     *
     * @return URI for this rule chain
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Get ID for this rule chain.
     *
     * @return UUID of this rule chain
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Get name of this rule chain.
     *
     * @return name of this rule chain.
     */
    public String getName() {
        return principalDto.getName();
    }

    /**
     * Gets tenant Id that owns this rule chian.
     *
     * @return tenant Id
     */
    public String getTenantId() {
        return principalDto.getTenantId();
    }

    /**
     * Sets name.
     *
     * @param name
     * @return this
     */
    public RuleChain name(String name) {
        principalDto.setName(name);
        return this;
    }

    /**
     * Sets tenant id.
     *
     * @param tenantId
     * @return this
     */
    public RuleChain tenantId(String tenantId) {
        principalDto.setTenantId(tenantId);
        return this;
    }


    /**
     * Add rule resource under this rule chain.
     *
     * @return new Rule()
     */

    public Rule addRule() {
        return new Rule(resource, principalDto.getRules(), new DtoRule());
    }

    /**
     * Returns collection of rules under this rule chain.
     *
     * @return collecion of rules
     */
    public ResourceCollection<Rule> getRules() {
        return getChildResources(
            principalDto.getRules(),
            null,
            VendorMediaType.APPLICATION_RULE_COLLECTION_JSON_V2,
            Rule.class,
            DtoRule.class);
    }

    @Override
    public String toString() {
        return String.format("RuleChain{id=%s, name=%s}", principalDto.getId(),
                             principalDto.getName());
    }
}
