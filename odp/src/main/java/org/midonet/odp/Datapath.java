/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Random;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.netlink.Translator;
import org.midonet.odp.OpenVSwitch.Datapath.Attr;

/**
 * Java representation of an OpenVSwitch Datapath object.
 */
public class Datapath {

    private final int index;
    private final String name;
    private final Stats stats;

    public Datapath(int index, String name, Stats stats) {
        this.name = name;
        this.index = index;
        this.stats = stats;
    }

    public Datapath(int index, String name) {
        this(index, name, new Stats(0L,0L,0L,0L));
    }

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
        String name = NetlinkMessage.readStringAttr(buf, Attr.Name);
        short id = Stats.trans.attrIdOf(null);
        Stats stats = NetlinkMessage.readAttr(buf, id, Stats.trans);
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

    /** Static stateless deserializer which builds a single Datapath instance
     *  and consumes the given ByteBuffer. */
    public static final Reader<Datapath> deserializer = new Reader<Datapath>() {
        public Datapath deserializeFrom(ByteBuffer buf) {
            if (buf == null)
                return null;
            return buildFrom(buf);
        }
    };

    /**
     * General datapath statistics
     */
    public static class Stats {

        private final long hits;
        private final long misses;
        private final long lost;
        private final long flows;

        public Stats(long hits, long misses, long lost, long flows) {
            this.hits = hits;
            this.misses = misses;
            this.lost = lost;
            this.flows = flows;
        }

        /** Get the number of flow table matches. */
        public long getHits() {
            return hits;
        }

        /** Get the number of flow table misses. */
        public long getMisses() {
            return misses;
        }

        /** Get the number of misses not sent to userspace. */
        public long getLost() {
            return lost;
        }

        /** Get the number of flows present */
        public long getFlows() {
            return flows;
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

    /**
     * Megaflows-specific datapath statistics
     */
    public static class MegaflowStats {

        private final long maskHits;
        private final int numMasks;

        public MegaflowStats(long hits, int num) {
            this.maskHits = hits;
            this.numMasks = num;
        }

        /** Get the number of masks used for flow lookups. */
        public long getMaskHits() {
            return maskHits;
        }

        /** Get the number of masks for the datapath. */
        public int getNumMasks() {
            return numMasks;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            @SuppressWarnings("unchecked") // safe cast
            MegaflowStats that = (MegaflowStats) o;

            return (this.maskHits == that.maskHits)
                   && (this.numMasks == that.numMasks);
        }

        @Override
        public int hashCode() {
            int result = Longs.hashCode(maskHits);
            return 31 * result + Ints.hashCode(numMasks);
        }

        @Override
        public String toString() {
            return "Stats{" +
                   "maskHits=" + maskHits +
                   ", num=" + numMasks +
                   '}';
        }

        public static final Translator<MegaflowStats> trans = new Translator<MegaflowStats>() {
            public short attrIdOf(MegaflowStats any) {
                return Attr.MegaflowStat;
            }
            public int serializeInto(ByteBuffer receiver, MegaflowStats value) {
                receiver.putLong(value.maskHits)
                    .putInt(value.numMasks);
                return 4 * 8;
            }
            public MegaflowStats deserializeFrom(ByteBuffer source) {
                long hits = source.getLong();
                int num = source.getInt();
                return new MegaflowStats(hits, num);
            }
        };

        public static MegaflowStats random() {
            return new MegaflowStats(r.nextLong(), r.nextInt());
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
