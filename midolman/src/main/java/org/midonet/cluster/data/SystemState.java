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

public class SystemState {

    public enum State {
        UPGRADE("UPGRADE"),
        ACTIVE("ACTIVE");

        private final String text;
        private State(String stateText) {
            this.text = stateText;
        }

        @Override
        public String toString() {
            return this.text;
        }
    }

    public enum Availability {
        READWRITE("READWRITE"),
        READONLY("READONLY");

        private final String text;
        private Availability(String stateText) {
            this.text = stateText;
        }

        @Override
        public String toString() {
            return this.text;
        }
    }

    Data data;

    public SystemState() {
        this(new Data());
    }

    public SystemState(@Nonnull Data data) {
        this.data = data;
    }

    protected SystemState self() {
        return this;
    }

    public SystemState setData(Data data) {
        this.data = data;
        return self();
    }

    public Data getData() {
        return this.data;
    }

    public SystemState setState(String state) {
        getData().state = state;
        return self();
    }

    public String getState() {
        return getData().state;
    }

    public SystemState setAvailability(String availability) {
        getData().availability = availability;
        return self();
    }

    public String getAvailability() {
        return getData().availability;
    }

    public SystemState setWriteVersion(String writeVersion) {
        getData().writeVersion = writeVersion;
        return self();
    }

    public String getWriteVersion() {
        return getData().writeVersion;
    }

    public static class Data {
        public String state;
        public String availability;
        public String writeVersion;
    }
}
