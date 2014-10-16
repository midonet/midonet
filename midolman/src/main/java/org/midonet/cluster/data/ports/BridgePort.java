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
package org.midonet.cluster.data.ports;

import java.util.UUID;

import org.midonet.cluster.data.Port;

import javax.annotation.Nonnull;

/**
 * Basic abstraction for a Bridge Port.
 */
public class BridgePort
        extends Port<BridgePort.Data, BridgePort>
{
    public BridgePort(UUID bridgeId, UUID uuid, Data portData){
        super(uuid, portData);
        if (getData() != null && bridgeId != null)
            setDeviceId(bridgeId);
    }

    public BridgePort(UUID uuid, Data data) {
        this(null, uuid, data);
    }

    public BridgePort(@Nonnull Data data) {
        this(null, null, data);
    }

    public BridgePort() {
        this(null, null, new Data());
    }

    @Override
    protected BridgePort self() {
        return this;
    }

    public BridgePort setVlanId(Short vlanId) {
        getData().vlanId = vlanId;
        return this;
    }

    public Short getVlanId() {
        return getData().vlanId;
    }

    public static class Data extends Port.Data {
        public Short vlanId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            Data data = (Data) o;

            if (vlanId != null ? !vlanId.equals(
                    data.vlanId) : data.vlanId != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (vlanId != null ? vlanId.hashCode() : 0);
            return result;
        }
    }
}
