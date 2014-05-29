/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
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

    public static class Data {
        public String state;
        public String availability;
    }
}
