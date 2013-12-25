/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.host;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.midonet.midolman.host.state.HostDirectory.Interface.Type;
import org.midonet.cluster.data.Entity;
import org.midonet.odp.DpPort;


/**
 * Host interface
 */
public class Interface extends Entity.Base<String, Interface.Data, Interface> {

    public enum Property {
        midonet_port_id
    }

    public Interface() {
        this(null, new Data());
    }

    public Interface(String id, Data data) {
        super(id, data);
    }

    @Override
    protected Interface self() {
        return this;
    }

    public String getName() {
        return getData().name;
    }

    public Interface setName(String name) {
        getData().name = name;
        return self();
    }

    public Type getType() {
        return getData().type;
    }

    public Interface setType(Type type) {
        getData().type = type;
        return self();
    }

    public String getEndpoint() {
        return getData().endpoint;
    }

    public Interface setEndpoint(String endpoint) {
        getData().endpoint = endpoint;
        return self();
    }

    public byte[] getMac() {
        return getData().mac;
    }

    public Interface setMac(byte[] mac) {
        getData().mac = mac;
        return self();
    }

    public int getStatus() {
        return getData().status;
    }

    public Interface setStatus(int status) {
        getData().status = status;
        return self();
    }

    public int getMtu() {
        return getData().mtu;
    }

    public Interface setMtu(int mtu) {
        getData().mtu = mtu;
        return self();
    }

    public InetAddress[] getAddresses() {
        return getData().addresses;
    }

    public Interface setAddresses(InetAddress[] addresses) {
        getData().addresses = addresses;
        return self();
    }

    public DpPort.Type getPortType() {
        return getData().portType;
    }

    public Interface setPortType(DpPort.Type portType) {
        getData().portType = portType;
        return self();
    }

    public static class Data {

        String name;
        Type type = Type.Unknown;
        String endpoint;
        byte[] mac;
        int status;
        int mtu;
        DpPort.Type portType;
        InetAddress[] addresses;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data that = (Data) o;

            if (mtu != that.mtu) return false;
            if (status != that.status) return false;
            if (!Arrays.equals(addresses, that.addresses)) return false;
            if (endpoint != null ? !endpoint.equals(
                    that.endpoint) : that.endpoint != null) return false;
            if (!Arrays.equals(mac, that.mac)) return false;
            if (!name.equals(that.name)) return false;
            if (type != that.type) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + type.hashCode();
            result = 31 * result + (endpoint != null ? endpoint.hashCode() : 0);
            result = 31 * result + Arrays.hashCode(mac);
            result = 31 * result + status;
            result = 31 * result + mtu;
            result = 31 * result + (addresses != null ? Arrays.hashCode(
                    addresses) : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Interface{" +
                    "name=" + name +
                    ", type=" + type +
                    ", endpoint='" + endpoint + '\'' +
                    ", mac=" + mac +
                    ", status=" + status +
                    ", mtu=" + mtu +
                    ", addresses=" + (addresses == null ? null : Arrays.asList(
                    addresses)) +
                    '}';
        }

        public Data() {
        }

        // Copy constructor
        public Data(Data original) {

            this.mtu = original.mtu;
            this.status = original.status;
            this.addresses = original.addresses.clone();
            this.endpoint = original.endpoint;
            this.mac = original.mac.clone();
            this.name = new String(original.name);
            this.type = original.type;
            this.portType = original.portType;
        }
    }
}
