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

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.google.common.base.Objects;

import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.util.collection.ListUtil;

@ZoomClass(clazz = Topology.Chain.class)
public class Chain extends UriResource {

    public static final int MIN_CHAIN_NAME_LEN = 1;
    public static final int MAX_CHAIN_NAME_LEN = 255;

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    private UUID id;

    @NotNull
    private String tenantId;

    @NotNull
    @Size(min = MIN_CHAIN_NAME_LEN, max = MAX_CHAIN_NAME_LEN)
    @ZoomField(name = "name")
    private String name;

    @JsonIgnore
    @ZoomField(name = "rule_ids", converter = UUIDUtil.Converter.class)
    private List<UUID> ruleIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.CHAINS, id);
    }

    public URI getRules() {
        return relativeUri(ResourceUris.RULES);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<UUID> getRuleIds() {
        return ruleIds;
    }

    public void setRuleIds(List<UUID> ruleIds) {
        this.ruleIds = ruleIds;
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Chain)) return false;
        final Chain other = (Chain) obj;

        return super.equals(other)
               && Objects.equal(id, other.id)
               && Objects.equal(name, other.name)
               && Objects.equal(tenantId, other.tenantId)
               && ListUtils.isEqualList(ruleIds, other.ruleIds);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(),
                                id, name, tenantId,
                                ListUtils.hashCodeForList(ruleIds));
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("URI", super.toString())
            .add("id", id)
            .add("name", name)
            .add("tenantId", tenantId)
            .add("ruleIds", ListUtil.toString(ruleIds))
            .toString();
    }

    public static class ChainData extends Chain {

        private URI uri;
        private URI rules;

        @Override
        public URI getUri() {
            return uri;
        }

        public void setUri(URI uri) {
            this.uri = uri;
        }

        @Override
        public URI getRules() {
            return rules;
        }

        public void setRules(URI rules) {
            this.rules = rules;
        }


        @Override
        public boolean equals(Object obj) {

            if (obj == this) return true;

            if (!(obj instanceof ChainData)) return false;
            final ChainData other = (ChainData) obj;

            return super.equals(other)
                   && Objects.equal(uri, other.uri)
                   && Objects.equal(rules, rules);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(super.hashCode(), uri, rules);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                .add("Chain", super.toString())
                .add("uri", uri)
                .add("rules", rules)
                .toString();
        }
    }
}
