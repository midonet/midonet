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
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Random;
import java.math.BigInteger;

import com.google.common.primitives.Longs;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.netlink.Translator;
import org.midonet.odp.OpenVSwitch.Port.Attr;
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
    protected DpPort(String name, int portNo) {
        this(name);
        this.portNo = portNo;
    }

    public enum Type {

        NetDev(OpenVSwitch.Port.Type.Netdev),
        Internal(OpenVSwitch.Port.Type.Internal),
        Gre(OpenVSwitch.Port.Type.Gre),
        VXLan(OpenVSwitch.Port.Type.VXLan),
        Gre64(OpenVSwitch.Port.Type.Gre64),
        Lisp(OpenVSwitch.Port.Type.Lisp);       // not yet supported

        public short typeId;

        Type(short typeId) {
            this.typeId = typeId;
        }
    }

    private final String name;
    private Integer portNo;
    private Stats stats;
    private FlowActionOutput outputAction;

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

    private void setPortNo(int portNo) {
        this.portNo = portNo;
        outputAction = output(portNo);
    }

    public FlowActionOutput toOutputAction() {
      return outputAction;
    }

    public void serializeInto(ByteBuffer buf) {
        NetlinkMessage.writeStringAttr(buf, Attr.Name, getName());

        NetlinkMessage.writeIntAttr(buf, Attr.Type, getType().typeId);

        if (getPortNo() != null) {
            NetlinkMessage.writeIntAttr(buf, Attr.PortNo, getPortNo());
        }

        if (stats != null) {
            NetlinkMessage.writeAttr(buf, stats, Stats.trans);
        }
    }

    protected void deserializeFrom(ByteBuffer buf) {
        int portNoPos = NetlinkMessage.seekAttribute(buf, Attr.PortNo);
        if (portNoPos >= 0) {
            this.setPortNo(buf.getInt(portNoPos));
        }
        int statPos = NetlinkMessage.seekAttribute(buf, Attr.Stats);
        if (statPos >= 0) {
            buf.position(statPos);
            this.stats = Stats.trans.deserializeFrom(buf);
        }
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
    public static DpPort buildFrom(ByteBuffer buf) {
        int actualDpIndex = buf.getInt(); // read the datapath id

        int typePos = NetlinkMessage.seekAttribute(buf, Attr.Type);
        String name = NetlinkMessage.readStringAttr(buf, Attr.Name);

        if (typePos < 0 || name == null)
            return null;

        DpPort port = newPortByTypeId((short) buf.getInt(typePos), name);

        if (port != null)
            port.deserializeFrom(buf);

        return port;
    }

    /** Stateless static deserializer function which builds a single DpPort
     *  instance by consumming the given ByteBuffer. */
    public static final Reader<DpPort> deserializer = new Reader<DpPort>() {
        public DpPort deserializeFrom(ByteBuffer buf) {
            if (buf == null)
                return null;
            return DpPort.buildFrom(buf);
        }
    };

    private static DpPort newPortByTypeId(short type, String name) {
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



    public static class Stats {
        long rxPackets, txPackets;
        long rxBytes, txBytes;
        long rxErrors, txErrors;
        long rxDropped, txDropped;

        public long getRxPackets() { return rxPackets; }
        public long getTxPackets() { return txPackets; }
        public long getRxBytes() { return rxBytes; }
        public long getTxBytes() { return txBytes; }
        public long getRxErrors() { return rxErrors; }
        public long getTxErrors() { return txErrors; }
        public long getRxDropped() { return rxDropped; }
        public long getTxDropped() { return txDropped; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            @SuppressWarnings("unchecked") // safe cast
            Stats that = (Stats) o;

            return (this.rxBytes == that.rxBytes)
                && (this.rxDropped == that.rxDropped)
                && (this.rxErrors == that.rxErrors)
                && (this.rxPackets == that.rxPackets)
                && (this.txBytes == that.txBytes)
                && (this.txDropped == that.txDropped)
                && (this.txErrors == that.txErrors)
                && (this.txPackets == that.txPackets);
        }

        @Override
        public int hashCode() {
            int result = Longs.hashCode(rxPackets);
            result = 31 * result + Longs.hashCode(txPackets);
            result = 31 * result + Longs.hashCode(rxBytes);
            result = 31 * result + Longs.hashCode(txBytes);
            result = 31 * result + Longs.hashCode(rxErrors);
            result = 31 * result + Longs.hashCode(txErrors);
            result = 31 * result + Longs.hashCode(rxDropped);
            return 31 * result + Longs.hashCode(txDropped);
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

        public static final Translator<Stats> trans = new Translator<Stats>() {
            public short attrIdOf(Stats any) {
                return Attr.Stats;
            }
            public int serializeInto(ByteBuffer receiver, Stats value) {
                receiver.putLong(value.rxPackets)
                        .putLong(value.txPackets)
                        .putLong(value.rxBytes)
                        .putLong(value.txBytes)
                        .putLong(value.rxErrors)
                        .putLong(value.txErrors)
                        .putLong(value.rxDropped)
                        .putLong(value.txDropped);
                return 8 * 8;
            }
            public Stats deserializeFrom(ByteBuffer source) {
                Stats s = new Stats();
                s.rxPackets = source.getLong();
                s.txPackets = source.getLong();
                s.rxBytes = source.getLong();
                s.txBytes = source.getLong();
                s.rxErrors = source.getLong();
                s.txErrors = source.getLong();
                s.rxDropped = source.getLong();
                s.txDropped = source.getLong();
                return s;
            }
        };

        public static Stats random() {
            ByteBuffer buf = ByteBuffer.allocate(8 * 8);
            r.nextBytes(buf.array());
            return trans.deserializeFrom(buf);
        }
    }

    /** mock method used in MockOvsDatapathConnection. */
    public static DpPort fakeFrom(DpPort port, int portNo) {
        DpPort fake = newPortByTypeId(port.getType().typeId, port.getName());
        fake.setPortNo(portNo);
        fake.stats = new DpPort.Stats();
        return fake;
    }

    public static DpPort random() {
        short[] types = new short[]{
            OpenVSwitch.Port.Type.Netdev,
            OpenVSwitch.Port.Type.Internal,
            OpenVSwitch.Port.Type.Gre,
            OpenVSwitch.Port.Type.VXLan
        };
        short type = types[r.nextInt(types.length)];
        String name = new BigInteger(100, r).toString(32);
        DpPort port = newPortByTypeId(type, name);
        if (port.getType() == Type.VXLan)
            port = VxLanTunnelPort.make(name, r.nextInt(30000));
        port.setPortNo(r.nextInt(100));
        port.stats = Stats.random();
        return port;
    }

    private static final Random r = new Random();
}
