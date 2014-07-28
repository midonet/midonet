/*
 * Copyright 2014 Midokura SARL
 */

package org.midonet.cassandra;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.*;
import org.midonet.util.eventloop.Reactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraClient {

    private static final Logger log =
            LoggerFactory.getLogger(CassandraClient.class);

    private Session session = null;

    private final String[] servers;
    private final String serversStr;
    private final String clusterName;
    private final String keyspaceName;
    private final Reactor reactor;
    private final int port;

    private final static Map<String, Cluster> CLUSTERS = new HashMap<>();
    private final static Map<String, Map<String, Session>> SESSIONS = new HashMap<>();

    public CassandraClient(String servers, String clusterName,
                           String keyspaceName, Reactor reactor) {
        this.serversStr = servers;
        this.clusterName = clusterName;
        this.keyspaceName = keyspaceName;
        this.reactor = reactor;
        this.servers  = servers.split(",");
        int p = 9042;
        for (int i=0; i<this.servers.length; i++) {
            String[] parts = this.servers[i].split(":");
            if (parts.length == 2) {
                this.servers[i] = parts[0];
                p = Integer.parseInt(parts[1]);
                break;
            }
        }
        this.port = p;
    }

    public void connect() {
        synchronized (CLUSTERS) {
            if (this.session != null)
                return;

            _connect();
        }
    }

    private void _connect() {
        Cluster cluster = CLUSTERS.get(serversStr);

        if (cluster == null) {
            LoadBalancingPolicy rr = new RoundRobinPolicy();
            LoadBalancingPolicy latencyAware = LatencyAwarePolicy.builder(rr).build();
            QueryOptions queryOpts = new QueryOptions().
                    setConsistencyLevel(ConsistencyLevel.QUORUM);
            SocketOptions sockOpts = new SocketOptions().
                    setKeepAlive(true).
                    setConnectTimeoutMillis(3000);

            cluster = Cluster.builder().
                    addContactPoints(servers).
                    withPort(this.port).
                    withRetryPolicy(DowngradingConsistencyRetryPolicy.INSTANCE).
                    withReconnectionPolicy(new ExponentialReconnectionPolicy(100L, 5000L)).
                    withCompression(ProtocolOptions.Compression.LZ4).
                    withLoadBalancingPolicy(latencyAware).
                    withQueryOptions(queryOpts).
                    withClusterName(clusterName).
                    withSocketOptions(sockOpts).build();

            CLUSTERS.put(serversStr, cluster);
            SESSIONS.put(serversStr, new HashMap<String, Session>());
        }

        Map<String, Session> sessions = SESSIONS.get(serversStr);
        this.session = sessions.get(keyspaceName);

        try {
            if (this.session == null) {
                this.session = cluster.connect(keyspaceName);
                sessions.put(keyspaceName, this.session);
                log.info("Connection to Cassandra key space {} ESTABLISHED", keyspaceName);
            }
        } catch (Exception e) {
            log.error("Connection to Cassandra key space " + keyspaceName + " FAILED", e);
            if (sessions.isEmpty()) {
                SESSIONS.remove(serversStr);
                cluster.close();
                CLUSTERS.remove(serversStr);
            }
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        log.info("Scheduling cassandra reconnection retry");
        if (reactor != null) {
            reactor.schedule(new Runnable() {
                @Override
                public void run() {
                    log.info("Trying to reconnect to cassandra");
                    connect();
                }
            }, 5, TimeUnit.SECONDS);
        } else {
            log.error("Permanently lost connection to cassandra and there is " +
                    "no reactor to schedule reconnects");
        }
    }

    public Session session() {
        return session;
    }
}
