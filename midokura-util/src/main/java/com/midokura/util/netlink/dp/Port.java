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

    public abstract PortOptions newOptions();

    public enum Type {
        NetDev, Internal, Patch, Gre, CapWap
    }

    protected abstract ActualPort self();

    Integer portNo;
    Type type;
    String name;
    byte[] address;
    PortOptions options;
    Stats stats;

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

    public Stats getStats() {
        return stats;
    }

    public void setStats(Stats stats) {
        this.stats = stats;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Port port = (Port) o;

        if (!Arrays.equals(address, port.address)) return false;
        if (name != null ? !name.equals(port.name) : port.name != null)
            return false;
        if (options != null ? !options.equals(
            port.options) : port.options != null)
            return false;
        if (portNo != null ? !portNo.equals(port.portNo) : port.portNo != null)
            return false;
        if (stats != null ? !stats.equals(port.stats) : port.stats != null)
            return false;
        if (type != port.type) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = portNo != null ? portNo.hashCode() : 0;
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (address != null ? Arrays.hashCode(address) : 0);
        result = 31 * result + (options != null ? options.hashCode() : 0);
        result = 31 * result + (stats != null ? stats.hashCode() : 0);
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
            ", stats=" + stats +
            '}';
    }

    public interface Options extends NetlinkMessage.BuilderAware { }

    public class Stats implements NetlinkMessage.BuilderAware {
        long rxPackets, txPackets;
        long rxBytes, txBytes;
        long rxErrors, txErrors;
        long rxDropped, txDropped;

        @Override
        public void serialize(NetlinkMessage.Builder builder) {
            builder.addValue(rxPackets);
            builder.addValue(txPackets);
            builder.addValue(rxBytes);
            builder.addValue(txBytes);
            builder.addValue(rxErrors);
            builder.addValue(txErrors);
            builder.addValue(rxDropped);
            builder.addValue(txDropped);
        }

        @Override
        public boolean deserialize(NetlinkMessage message) {
            try {
                rxPackets = message.getLong();
                txPackets = message.getLong();
                rxBytes = message.getLong();
                txBytes = message.getLong();
                rxErrors = message.getLong();
                txErrors = message.getLong();
                rxDropped = message.getLong();
                txDropped = message.getLong();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Stats stats = (Stats) o;

            if (rxBytes != stats.rxBytes) return false;
            if (rxDropped != stats.rxDropped) return false;
            if (rxErrors != stats.rxErrors) return false;
            if (rxPackets != stats.rxPackets) return false;
            if (txBytes != stats.txBytes) return false;
            if (txDropped != stats.txDropped) return false;
            if (txErrors != stats.txErrors) return false;
            if (txPackets != stats.txPackets) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = (int) (rxPackets ^ (rxPackets >>> 32));
            result = 31 * result + (int) (txPackets ^ (txPackets >>> 32));
            result = 31 * result + (int) (rxBytes ^ (rxBytes >>> 32));
            result = 31 * result + (int) (txBytes ^ (txBytes >>> 32));
            result = 31 * result + (int) (rxErrors ^ (rxErrors >>> 32));
            result = 31 * result + (int) (txErrors ^ (txErrors >>> 32));
            result = 31 * result + (int) (rxDropped ^ (rxDropped >>> 32));
            result = 31 * result + (int) (txDropped ^ (txDropped >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "Stats{" +
                "rxPackets=" + rxPackets +
                ", txPackets=" + txPackets +
                ", rxBytes=" + rxBytes +
                ", txBytes=" + txBytes +
                ", rxErrors=" + rxErrors +
                ", txErrors=" + txErrors +
                ", rxDropped=" + rxDropped +
                ", txDropped=" + txDropped +
                '}';
        }
    }
}
