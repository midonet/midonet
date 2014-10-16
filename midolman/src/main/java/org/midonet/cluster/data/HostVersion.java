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

import javax.annotation.Nonnull;
import java.util.UUID;

public class HostVersion {
    Data data;

    public HostVersion() {
        this(new Data());
    }

    public HostVersion(@Nonnull Data data) {
        this.data = data;
    }

    protected HostVersion self() {
        return this;
    }

    public HostVersion setData(Data data) {
        this.data = data;
        return self();
    }

    public Data getData() {
        return this.data;
    }

    public HostVersion setVersion(String version) {
        getData().version = version;
        return self();
    }

    public String getVersion() {
        return getData().version;
    }

    public HostVersion setHostId(UUID id) {
        getData().hostId = id;
        return self();
    }

    public UUID getHostId() {
        return getData().hostId;
    }

    public static class Data {
        public String version;
        public UUID hostId;
    }
}
