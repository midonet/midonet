/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp;

import com.midokura.util.netlink.NetlinkMessage;

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

    public class Stats implements NetlinkMessage.BuilderAware {

        long hits;
        long misses;
        long lost;
        long flows;

        public long getHits() {
            return hits;
        }

        public void setHits(long hits) {
            this.hits = hits;
        }

        public long getMisses() {
            return misses;
        }

        public void setMisses(long misses) {
            this.misses = misses;
        }

        public long getLost() {
            return lost;
        }

        public void setLost(long lost) {
            this.lost = lost;
        }

        public long getFlows() {
            return flows;
        }

        public void setFlows(long flows) {
            this.flows = flows;
        }

        @Override
        public void serialize(NetlinkMessage.Builder builder) {
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
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Datapath datapath = (Datapath) o;

        if (index != datapath.index) return false;
        if (!name.equals(datapath.name)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = index;
        result = 31 * result + name.hashCode();
        return result;
    }
}
