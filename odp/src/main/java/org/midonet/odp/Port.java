/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import java.util.Arrays;
import java.util.EnumSet;
import javax.annotation.Nonnull;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.netlink.messages.BuilderAware;

/**
 * Abstract port abstraction.
 */
public abstract class Port<PortOptions extends org.midonet.odp.PortOptions, ActualPort extends Port<PortOptions, ActualPort>> {

    protected Port(@Nonnull String name, @Nonnull Type type) {
        this.name = name;
        this.type = type;
    }

    public abstract PortOptions newOptions();

    public enum Type {
        NetDev, Internal, Patch, Gre, CapWap;

        public static EnumSet<Type> Tunnels = EnumSet.of(Patch, Gre, CapWap);
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

    public ActualPort setOptions() {
        return setOptions(newOptions());
    }

    public PortOptions getOptions() {
        return options;
    }

    public Stats getStats() {
        return stats;
    }

    public ActualPort setStats(Stats stats) {
        this.stats = stats;
        return self();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        Port port = (Port) o;

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
            ", address=" + macAddressAsString(address) +
            ", options=" + options +
            ", stats=" + stats +
            '}';
    }

    private String macAddressAsString(byte[] address) {
        if (address == null ) {
            return "null";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02x", address[0]));
        for (int i=1; i<address.length; i++)
            sb.append(":").append(String.format("%02x", address[i]));

        return sb.toString();
    }

    public static class Stats implements BuilderAware {
        long rxPackets, txPackets;
        long rxBytes, txBytes;
        long rxErrors, txErrors;
        long rxDropped, txDropped;


        public long getRxPackets() {return rxPackets;}
        public long getTxPackets() {return txPackets;}
        public long getRxBytes() {return rxBytes;}
        public long getTxBytes() {return txBytes;}
        public long getRxErrors() {return rxErrors;}
        public long getTxErrors() {return txErrors;}
        public long getRxDropped() {return rxDropped;}
        public long getTxDropped() {return txDropped;}

        @Override
        public void serialize(BaseBuilder builder) {
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
