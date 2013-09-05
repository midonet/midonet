/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.netlink.messages.BuilderAware;

/**
 * Datapath abstraction.
 */
public class Datapath {

    public Datapath(int index, String name) {
        this.name = name;
        this.index = index;
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

    public class Stats implements BuilderAware {
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
        public void serialize(BaseBuilder<?,?> builder) {
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
