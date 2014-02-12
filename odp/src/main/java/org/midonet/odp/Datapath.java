/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.base.Function;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.family.DatapathFamily;

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

    Integer index;
    String name;
    Stats stats;

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Stats getStats() {
        return stats;
    }

    public void setStats(Stats stats) {
        this.stats = stats;
    }

    public static Datapath buildFrom(NetlinkMessage msg) {
        Integer index = msg.getInt();
        String name = msg.getAttrValueString(DatapathFamily.Attr.NAME);
        Stats stats = Stats.buildFrom(msg);
        return new Datapath(index, name, stats);
    }

    /** Static stateless deserializer which builds a single Datapath instance.
     *  Only consumes the head ByteBuffer in the given input List. */
    public static final Function<List<ByteBuffer>,Datapath> deserializer =
        new Function<List<ByteBuffer>, Datapath>() {
            @Override
            public Datapath apply(List<ByteBuffer> input) {
                if (input == null || input.isEmpty() || input.get(0) == null)
                    return null;
                return buildFrom(new NetlinkMessage(input.get(0)));
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
                    datapaths.add(buildFrom(new NetlinkMessage(buffer)));
                }
                return datapaths;
            }
        };

    public static class Stats implements BuilderAware {
        long hits;
        long misses;
        long lost;
        long flows;

        public long getHits() {
            return hits;
        }

        public Stats setHits(long hits) {
            this.hits = hits;
            return this;
        }

        public long getMisses() {
            return misses;
        }

        public Stats setMisses(long misses) {
            this.misses = misses;
            return this;
        }

        public long getLost() {
            return lost;
        }

        public Stats setLost(long lost) {
            this.lost = lost;
            return this;
        }

        public long getFlows() {
            return flows;
        }

        public Stats setFlows(long flows) {
            this.flows = flows;
            return this;
        }

        @Override
        public void serialize(Builder builder) {
            builder.addValue(hits);
            builder.addValue(misses);
            builder.addValue(lost);
            builder.addValue(flows);
        }

        @Override
        public boolean deserialize(NetlinkMessage message) {
            try {
                hits = message.getLong();
                misses = message.getLong();
                lost = message.getLong();
                flows = message.getLong();
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

            if (flows != stats.flows) return false;
            if (hits != stats.hits) return false;
            if (lost != stats.lost) return false;
            if (misses != stats.misses) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = (int) (hits ^ (hits >>> 32));
            result = 31 * result + (int) (misses ^ (misses >>> 32));
            result = 31 * result + (int) (lost ^ (lost >>> 32));
            result = 31 * result + (int) (flows ^ (flows >>> 32));
            return result;
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

        public static Stats buildFrom(NetlinkMessage msg) {
            return msg.getAttrValue(DatapathFamily.Attr.STATS, new Stats());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Datapath datapath = (Datapath) o;

        if (index != null ? !index.equals(
            datapath.index) : datapath.index != null)
            return false;
        if (name != null ? !name.equals(datapath.name) : datapath.name != null)
            return false;
        if (stats != null ? !stats.equals(
            datapath.stats) : datapath.stats != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = index != null ? index.hashCode() : 0;
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (stats != null ? stats.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Datapath{" +
            "index=" + index +
            ", name='" + name + '\'' +
            ", stats=" + stats +
            '}';
    }
}
