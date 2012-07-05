/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp;

import java.util.Arrays;
import javax.annotation.Nonnull;

import com.midokura.util.netlink.NetlinkMessage;

/**
 * Abstract port abstraction.
 */
public abstract class Port<PortOptions extends Port.Options, ActualPort extends Port<PortOptions, ActualPort>> {

    protected Port(@Nonnull String name, @Nonnull Type type) {
        this.name = name;
        this.type = type;
    }
    public enum Type {
        NetDev, Internal, Patch, Gre, CapWap
    }

    protected abstract ActualPort self();

    Integer portNo;
    Type type;
    String name;
    byte[] address;
    PortOptions options;

    public Integer getPortNo() {
        return portNo;
    }

    public ActualPort setPortNo(Integer portNo) {
        this.portNo = portNo;
        return self();
    }

    public Type getType() {
        return type;
    }

    public ActualPort setType(Type type) {
        this.type = type;
        return self();
    }

    public String getName() {
        return name;
    }

    public ActualPort setName(String name) {
        this.name = name;
        return self();
    }

    public byte[] getAddress() {
        return address;
    }

    public ActualPort setAddress(byte[] address) {
        this.address = address;
        return self();
    }

    public ActualPort setOptions(PortOptions options) {
        this.options = options;
        return self();
    }

    public PortOptions getOptions() {
        return options;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Port port = (Port) o;

        if (!Arrays.equals(address, port.address)) return false;
        if (!name.equals(port.name)) return false;
        if (options != null ? !options.equals(
            port.options) : port.options != null)
            return false;
        if (portNo != null ? !portNo.equals(port.portNo) : port.portNo != null)
            return false;
        if (type != port.type) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = portNo != null ? portNo.hashCode() : 0;
        result = 31 * result + type.hashCode();
        result = 31 * result + name.hashCode();
        result = 31 * result + (address != null ? Arrays.hashCode(address) : 0);
        result = 31 * result + (options != null ? options.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Port{" +
            "portNo=" + portNo +
            ", type=" + type +
            ", name='" + name + '\'' +
            ", address=" + Arrays.toString(address) +
            ", options=" + options +
            '}';
    }

    public interface Options extends NetlinkMessage.BuilderAware { }
}
