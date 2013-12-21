/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import java.util.Arrays;
import java.util.EnumSet;
import javax.annotation.Nonnull;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.netlink.messages.Builder;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.family.PortFamily;
import org.midonet.odp.flows.FlowActionOutput;
import org.midonet.odp.flows.FlowActions;

/**
 * Abstract port abstraction.
 */
public abstract class Port<Opts extends PortOptions, Self extends Port<Opts, Self>> {

    protected Port(@Nonnull String name, @Nonnull Type type) {
        this.name = name;
        this.type = type;
    }

    public abstract Opts newOptions();

    public enum Type {

        NetDev(OpenVSwitch.Port.Type.Netdev),
        Internal(OpenVSwitch.Port.Type.Internal),
        Gre(OpenVSwitch.Port.Type.Gre),
        VXLan(OpenVSwitch.Port.Type.VXLan),     // not yet supported
        Gre101(OpenVSwitch.Port.Type.GreOld),   // ovs 1.9 gre compatibility
        Gre64(OpenVSwitch.Port.Type.Gre64),
        Lisp(OpenVSwitch.Port.Type.Lisp);       // not yet supported

        public int attrId;

        Type(int attr) {
            this.attrId = attr;
        }

        public static EnumSet<Type> Tunnels =
            EnumSet.of(Gre, VXLan, Lisp, Gre101, Gre64);
    }

    protected abstract Self self();

    Integer portNo;
    Type type;
    String name;
    Opts options;
    Stats stats;

    public Integer getPortNo() {
        return portNo;
    }

    public Self setPortNo(Integer portNo) {
        this.portNo = portNo;
        return self();
    }

    public Type getType() {
        return type;
    }

    public Self setType(Type type) {
        this.type = type;
        return self();
    }

    public String getName() {
        return name;
    }

    public Self setName(String name) {
        this.name = name;
        return self();
    }

    public Self setOptions(Opts options) {
        this.options = options;
        return self();
    }

    public Self setOptions() {
        return setOptions(newOptions());
    }

    public Opts getOptions() {
        return options;
    }

    public Stats getStats() {
        return stats;
    }

    public Self setStats(Stats stats) {
        this.stats = stats;
        return self();
    }

    public FlowActionOutput toOutputAction() {
      return FlowActions.output(this.portNo.shortValue());
    }

    public void serializeInto(Builder builder) {
        builder.addAttr(PortFamily.Attr.NAME, getName());
        builder.addAttr(PortFamily.Attr.PORT_TYPE, getType().attrId);
        if (getPortNo() != null)
            builder.addAttr(PortFamily.Attr.PORT_NO, getPortNo());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked") // safe cast
        Port<?,?> port = (Port) o;

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
            ", options=" + options +
            ", stats=" + stats +
            '}';
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
        public void serialize(BaseBuilder<?,?> builder) {
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
