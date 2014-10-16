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
package org.midonet.cluster.data;

import org.codehaus.jackson.annotate.JsonTypeInfo;

import javax.annotation.Nonnull;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
              include = JsonTypeInfo.As.PROPERTY, property = "type")
public class RouterName extends Entity.Base<String, RouterName.Data, RouterName>{

    public RouterName(String key, Data data) {
        super(key, data);
    }

    public RouterName(@Nonnull Router router) {
        super(router.getData().name, new Data());

        setRouterId(router.getId());
    }

    public RouterName setRouterId(UUID routerId) {
        getData().id = routerId;
        return self();
    }

    public UUID getRouterId() {
        return getData().id;
    }

    @Override
    protected RouterName self() {
        return this;
    }

    public static class Data {
        public UUID id;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (id != null ? !id.equals(data.id) : data.id != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return id != null ? id.hashCode() : 0;
        }
    }
}
