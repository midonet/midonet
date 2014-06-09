/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import com.google.common.base.Function;
import com.google.common.primitives.Longs;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Translator;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.family.DatapathFamily;
import org.midonet.odp.OpenVSwitch.Datapath.Attr;

/**
 * Java representation of an OpenVSwitch Datapath object.
 */
public class Datapath {

    public Datapath(int index, String name) {
        this.name = name;
        this.index = index;
        this.stats = new Stats();
    }

    public Datapath(int index, String name, Stats stats) {
        this.name = name;
        this.index = index;
        this.stats = stats;
    }

    private int index;
    private String name;
    private Stats stats;

    public int getIndex() {
        return index;
    }

    public String getName() {
        return name;
    }

    public Stats getStats() {
        return stats;
    }

    public static Datapath buildFrom(ByteBuffer buf) {
        int index = buf.getInt();
        String name = NetlinkMessage.getAttrValueString(buf, Attr.Name);
        Stats stats = Stats.buildFrom(buf);
        return new Datapath(index, name, stats);
    }

    /** This function is only used in test, as all dp commands directly
     *  write the request message. See get/enum/createRequest(). */
    public void serializeInto(ByteBuffer buf) {
        buf.putInt(index);

        NetlinkMessage.writeStringAttr(buf, Attr.Name, name);

        if (stats != null) {
            NetlinkMessage.writeAttr(buf, stats, Stats.trans);
        }
    }

    /** Static stateless deserializer which builds a single Datapath instance.
     *  Only consumes the head ByteBuffer in the given input List. */
    public static final Function<List<ByteBuffer>,Datapath> deserializer =
        new Function<List<ByteBuffer>, Datapath>() {
            @Override
            public Datapath apply(List<ByteBuffer> input) {
                if (input == null || input.isEmpty() || input.get(0) == null)
                    return null;
                return buildFrom(input.get(0));
            }
        };

    /** Static stateless deserializer which builds a set of Datapath instances.
     *  Consumes all ByteBuffers in the given input List. */
    public static final Function<List<ByteBuffer>,Set<Datapath>> setDeserializer =
        new Function<List<ByteBuffer>, Set<Datapath>>() {
            @Override
            public Set<Datapath> apply(List<ByteBuffer> input) {
                Set<Datapath> datapaths = new HashSet<Datapath>();
                if (input == null)
                    return datapaths;
                for (ByteBuffer buffer : input) {
                    datapaths.add(buildFrom(buffer));
                }
                return datapaths;
            }
        };

    public static class Stats implements BuilderAware {

        private long hits;
        private long misses;
        private long lost;
        private long flows;

        public Stats() { }

        public Stats(long hits, long misses, long lost, long flows) {
            this.hits = hits;
            this.misses = misses;
            this.lost = lost;
            this.flows = flows;
        }

        public long getHits() {
            return hits;
        }

        public long getMisses() {
            return misses;
        }

        public long getLost() {
            return lost;
        }

        public long getFlows() {
            return flows;
        }

        @Override
        public boolean deserialize(ByteBuffer buf) {
            try {
                hits = buf.getLong();
                misses = buf.getLong();
                lost = buf.getLong();
                flows = buf.getLong();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            @SuppressWarnings("unchecked") // safe cast
            Stats that = (Stats) o;

            return (this.hits == that.hits)
                && (this.misses == that.misses)
                && (this.lost == that.lost)
                && (this.flows == that.flows);
        }

        @Override
        public int hashCode() {
            int result = Longs.hashCode(hits);
            result = 31 * result + Longs.hashCode(misses);
            result = 31 * result + Longs.hashCode(lost);
            return 31 * result + Longs.hashCode(flows);
        }

        @Override
        public String toString() {
            return "Stats{" +
                "hits=" + hits +
                ", misses=" + misses +
                ", lost=" + lost +
                ", flows=" + flows +
                '}';
        }

        public static Stats buildFrom(ByteBuffer buf) {
            return NetlinkMessage.getAttrValue(buf, Attr.Stat, new Stats());
        }

        public static final Translator<Stats> trans = new Translator<Stats>() {
            public short attrIdOf(Stats any) {
                return Attr.Stat;
            }
            public int serializeInto(ByteBuffer receiver, Stats value) {
                receiver.putLong(value.hits)
                        .putLong(value.misses)
                        .putLong(value.lost)
                        .putLong(value.flows);
                return 4 * 8;
            }
            public Stats deserializeFrom(ByteBuffer source) {
                long hits = source.getLong();
                long misses = source.getLong();
                long lost = source.getLong();
                long flows = source.getLong();
                return new Stats(hits, misses, lost, flows);
            }
        };

        public static Stats random() {
            return new Stats(r.nextLong(), r.nextLong(),
                             r.nextLong(), r.nextLong());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked") // safe cast
        Datapath that = (Datapath) o;

        return Objects.equals(this.index, that.index)
            && Objects.equals(this.name, that.name)
            && Objects.equals(this.stats, that.stats);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, name, stats);
    }

    @Override
    public String toString() {
        return "Datapath{" +
            "index=" + index +
            ", name='" + name + '\'' +
            ", stats=" + stats +
            '}';
    }

    public static ByteBuffer getRequest(ByteBuffer buf, int datapathId,
                                        String name) {
        buf.putInt(datapathId);
        if (name != null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, name);
        }
        buf.flip();
        return buf;
    }

    public static ByteBuffer enumRequest(ByteBuffer buf) {
        buf.putInt(0);
        buf.flip();
        return buf;
    }

    public static ByteBuffer createRequest(ByteBuffer buf, int pid,
                                           String name) {
        buf.putInt(0);
        NetlinkMessage.writeIntAttr(buf, Attr.UpcallPID, pid);
        if (name != null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, name);
        }
        buf.flip();
        return buf;
    }

    public static Datapath random() {
        return new Datapath(1 + r.nextInt(100),
                            new BigInteger(100, r).toString(32),
                            Stats.random());
    }

    private static final Random r = new Random();
}
