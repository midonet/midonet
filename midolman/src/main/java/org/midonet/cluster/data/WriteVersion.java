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

public class WriteVersion {

    Data data;

    public WriteVersion() {
        this(new Data());
    }
    public WriteVersion(@Nonnull Data data) {
        this.data = data;
    }

    protected WriteVersion self() {
        return this;
    }

    public Data getData() {
        return data;
    }

    public WriteVersion setData(Data data) {
        this.data = data;
        return self();
    }

    public WriteVersion setVersion(String version) {
        getData().version = version;
        return self();
    }

    public String getVersion() {
        return getData().version;
    }

    public static class Data {
        public String version;
    }
}
