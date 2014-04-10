/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;

import com.google.common.base.Function;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.family.PortFamily;
import org.midonet.odp.flows.FlowActionOutput;
import org.midonet.odp.ports.*;

import static org.midonet.odp.flows.FlowActions.output;

/**
 * Base Datapath Port class.
 */
public abstract class DpPort {

    protected DpPort(String name) {
        assert name != null;
        this.name = name;
    }

    public enum Type {

        NetDev(OpenVSwitch.Port.Type.Netdev),
        Internal(OpenVSwitch.Port.Type.Internal),
        Gre(OpenVSwitch.Port.Type.Gre),
        VXLan(OpenVSwitch.Port.Type.VXLan),
        Gre64(OpenVSwitch.Port.Type.Gre64),
        Lisp(OpenVSwitch.Port.Type.Lisp);       // not yet supported

        public int attrId;

        Type(int attr) {
            this.attrId = attr;
        }
    }

    private final String name;
    private Integer portNo;
    private Stats stats;

    abstract public Type getType();

    public Integer getPortNo() {
        return portNo;
    }

    public String getName() {
        return name;
    }

    public Stats getStats() {
        return stats;
    }

    public FlowActionOutput toOutputAction() {
      return output(this.portNo.shortValue());
    }

    public void serializeInto(ByteBuffer buf) {
        short nameAttrId = (short) OpenVSwitch.Port.Attr.Name;
        NetlinkMessage.addAttribute(buf, nameAttrId, getName());

        short portTypeAttrId = (short) OpenVSwitch.Port.Attr.Type;
        NetlinkMessage.writeIntAttr(buf, portTypeAttrId, getType().attrId);

        if (getPortNo() != null) {
            short portNoAttrId = (short) OpenVSwitch.Port.Attr.PortNo;
            NetlinkMessage.writeIntAttr(buf, portNoAttrId, getPortNo());
        }
    }

    protected void deserializeFrom(NetlinkMessage msg) {
        this.portNo = msg.getAttrValueInt(PortFamily.Attr.PORT_NO);
        this.stats = Stats.buildFrom(msg);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked") // safe cast
        DpPort that = (DpPort) o;

        return (getType() == that.getType())
            && Objects.equals(name, that.name)
            && Objects.equals(portNo, that.portNo)
            && Objects.equals(stats, that.stats);
    }

    @Override
    public int hashCode() {
        return Objects.hash(portNo, getType(), name, stats);
    }

    @Override
    public String toString() {
        return "DpPort{" +
            "portNo=" + portNo +
            ", type=" + getType() +
            ", name='" + name + '\'' +
            ", stats=" + stats +
            '}';
    }

    /** Factory method which builds DpPort instances from NetlinkMessages.
     *  Consumes the underlying ByteBuffer.*/
    public static DpPort buildFrom(NetlinkMessage msg) {
        int actualDpIndex = msg.getInt(); // read the datapath id

        String name = msg.getAttrValueString(PortFamily.Attr.NAME);
        Integer type = msg.getAttrValueInt(PortFamily.Attr.PORT_TYPE);
        if (type == null || name == null)
            return null;

        DpPort port = newPortByTypeId(type, name);

        if (port != null)
            port.deserializeFrom(msg);

        return port;
    }

    /** Stateless static deserializer function which builds single ports once
     *  at a time. Consumes the head ByteBuffer of the input List.*/
    public static final Function<List<ByteBuffer>, DpPort> deserializer =
        new Function<List<ByteBuffer>, DpPort>() {
            @Override
            public DpPort apply(List<ByteBuffer> input) {
                if (input == null || input.isEmpty() || input.get(0) == null)
                    return null;
                return DpPort.buildFrom(new NetlinkMessage(input.get(0)));
            }
        };

    /** Stateless static deserializer function which builds sets of ports.
     *  Consumes all ByteBuffer in the input List.*/
    public static final Function<List<ByteBuffer>, Set<DpPort>> setDeserializer =
        new Function<List<ByteBuffer>, Set<DpPort>>() {
            @Override
            public Set<DpPort> apply(List<ByteBuffer> input) {
                Set<DpPort> ports = new HashSet<>();
                if (input == null)
                    return ports;
                for (ByteBuffer buffer : input) {
                    ports.add(DpPort.buildFrom(new NetlinkMessage(buffer)));
                }
                ports.remove(null);
                return ports;
            }
        };

    private static DpPort newPortByTypeId(Integer type, String name) {
        switch (type) {

            case OpenVSwitch.Port.Type.Netdev:
                return new NetDevPort(name);

            case OpenVSwitch.Port.Type.Internal:
                return new InternalPort(name);

            case OpenVSwitch.Port.Type.Gre:
                return new GreTunnelPort(name);

            case OpenVSwitch.Port.Type.Gre64:
                return new GreTunnelPort(name);

            case OpenVSwitch.Port.Type.VXLan:
                return new VxLanTunnelPort(name);

            default:
                return null;
        }
    }

    public static ByteBuffer getRequest(ByteBuffer buf, int datapathId, int pid,
                                        String portName, Integer portId) {
        buf.putInt(datapathId);

        short upcallPidAttrId = (short) OpenVSwitch.Port.Attr.UpcallPID;
        NetlinkMessage.writeIntAttr(buf, upcallPidAttrId, pid);

        if (portId != null) {
            short portNoAttrId = (short) OpenVSwitch.Port.Attr.PortNo;
            NetlinkMessage.writeIntAttr(buf, portNoAttrId, portId);
        }

        if (portName != null) {
            short nameAttrId = (short) OpenVSwitch.Port.Attr.Name;
            NetlinkMessage.addAttribute(buf, nameAttrId, portName);
        }

        buf.flip();
        return buf;
    }

    public static ByteBuffer createRequest(ByteBuffer buf, int datapathId,
                                           int pid, DpPort port) {
        buf.putInt(datapathId);
        short upcallPidAttrId = (short) OpenVSwitch.Port.Attr.UpcallPID;
        NetlinkMessage.writeIntAttr(buf, upcallPidAttrId, pid);
        port.serializeInto(buf);
        buf.flip();
        return buf;
    }

    public static ByteBuffer deleteRequest(ByteBuffer buf, int datapathId,
                                           DpPort port) {
        buf.putInt(datapathId);
        short portNoAttrId = (short) OpenVSwitch.Port.Attr.PortNo;
        NetlinkMessage.writeIntAttr(buf, portNoAttrId, port.getPortNo());
        buf.flip();
        return buf;
    }

    public static ByteBuffer enumRequest(ByteBuffer buf, int datapathId) {
        buf.putInt(datapathId);
        buf.flip();
        return buf;
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
        public void serialize(Builder builder) {
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

        public static Stats buildFrom(NetlinkMessage msg) {
            return msg.getAttrValue(PortFamily.Attr.STATS, new Stats());
        }
    }

    /** mock method used in MockOvsDatapathConnection. */
    public static DpPort fakeFrom(DpPort port, int portNo) {
        DpPort fake = newPortByTypeId(port.getType().attrId, port.getName());
        fake.portNo = portNo;
        fake.stats = new DpPort.Stats();
        return fake;
    }

}
