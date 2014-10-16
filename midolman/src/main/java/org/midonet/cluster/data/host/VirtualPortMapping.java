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
package org.midonet.cluster.data.host;

import org.midonet.cluster.data.Entity;

import java.util.UUID;

/**
 * Host virtual port mapping
 */
public class VirtualPortMapping extends
        Entity.Base<UUID, VirtualPortMapping.Data, VirtualPortMapping> {

    public VirtualPortMapping() {
        this(null, new Data());
    }

    public VirtualPortMapping(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected VirtualPortMapping self() {
        return this;
    }

    public UUID getVirtualPortId() {
        return getData().virtualPortId;
    }

    public VirtualPortMapping setVirtualPortId(UUID virtualPortId) {
        getData().virtualPortId = virtualPortId;
        return self();
    }

    public String getLocalDeviceName() {
        return getData().localDeviceName;
    }

    public VirtualPortMapping setLocalDeviceName(String localDeviceName) {
        getData().localDeviceName = localDeviceName;
        return self();
    }

    public static class Data {

        public UUID virtualPortId; // TODO: Do we need this?
        public String localDeviceName;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data that = (Data) o;

            if (localDeviceName != null ? !localDeviceName.equals(
                    that.localDeviceName) : that.localDeviceName != null)
                return false;
            if (virtualPortId != null ? !virtualPortId.equals(
                    that.virtualPortId) : that.virtualPortId != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = virtualPortId != null ? virtualPortId.hashCode() : 0;
            result = 31 * result + (localDeviceName != null
                    ? localDeviceName.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "VirtualPortMapping{" +
                    "virtualPortId=" + virtualPortId +
                    ", localDeviceName='" + localDeviceName + '\'' +
                    '}';
        }
    }
}
