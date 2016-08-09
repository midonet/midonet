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

import java.util.UUID;

public class PortActiveTunnelKey
    extends Entity.Base<UUID, PortActiveTunnelKey.Data, PortActiveTunnelKey> {
    public final static PortActiveTunnelKey EMPTY = new PortActiveTunnelKey();

    public PortActiveTunnelKey() {
        super(null, new Data());
    }

    @Override
    protected PortActiveTunnelKey self() {
        return this;
    }

    public boolean hasTunnelKey() {
        return getData().tunnelKey == Data.NO_KEY;
    }

    public PortActiveTunnelKey setTunnelKey(long tunnelKey) {
        getData().tunnelKey = tunnelKey;
        return this;
    }

    public long getTunnelKey() {
        return getData().tunnelKey;
    }

    @Override
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (other == this)
            return true;
        if (!(other instanceof PortActiveTunnelKey))
            return false;
        PortActiveTunnelKey patk = (PortActiveTunnelKey)other;
        return getData().equals(patk.getData());
    }

    @Override
    public int hashCode() {
        return getData().hashCode();
    }

    public static class Data {
        private static long NO_KEY = -12345;
        public long tunnelKey;

        public Data() {
            this.tunnelKey = NO_KEY;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof Data))
                return false;
            Data patk = (Data) other;
            return patk.tunnelKey == tunnelKey;
        }

        @Override
        public int hashCode() {
            return Long.valueOf(tunnelKey).hashCode();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(tunnelKey);
            return sb.toString();
        }

    }
}
