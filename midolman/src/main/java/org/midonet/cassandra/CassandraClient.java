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

package org.midonet.cassandra;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.*;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.TryCatchReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;

public class CassandraClient {

    private static final Logger log =
            LoggerFactory.getLogger(CassandraClient.class);

    private Session session = null;

    private Promise<Session> sessionPromise = Promise$.MODULE$.apply();

    private final String[] servers;
    private final String serversStr;
    private final String clusterName;
    private final String keyspaceName;
    private final Reactor reactor;
    private final int port;
    private final int replicationFactor;
    private String[] schema;


    private static Reactor theReactor = null;
    private static Object creationLock = new Object();
    private static Reactor makeReactor() {
        synchronized (creationLock) {
            if (theReactor == null)
                theReactor = new TryCatchReactor("cassandra-connection-manager", 1);
        }
        return theReactor;
    }

    private final static Map<String, Cluster> CLUSTERS = new HashMap<>();
    private final static Map<String, Map<String, Session>> SESSIONS = new HashMap<>();

    public CassandraClient(String servers, String clusterName,
                           String keyspaceName, int replicationFactor,
                           String[] schema) {
        this.serversStr = servers;
        this.clusterName = clusterName;
        this.keyspaceName = keyspaceName;
        this.reactor = makeReactor();
        this.servers  = servers.split(",");
        this.schema = schema;
        this.replicationFactor = replicationFactor;
        int p = 9042;
        for (int i = this.servers.length-1; i >= 0; i--) {
            String[] parts = this.servers[i].split(":");
            if (parts.length == 2) {
                this.servers[i] = parts[0];
                p = Integer.parseInt(parts[1]);
            }
        }
        this.port = p;
    }

    public Future<Session> connect() {
        reactor.submit(new Runnable() {
            @Override
            public void run() {
                synchronized(CLUSTERS) {
                    if (session == null)
                        _connect(10);
                }
            }
        });
        return sessionPromise.future();
    }

    private void createAndUseKeyspace() {
        String q = "CREATE KEYSPACE IF NOT EXISTS " + keyspaceName +
            " WITH REPLICATION = { 'class' : 'SimpleStrategy', " +
                                  "'replication_factor' : " + replicationFactor + "};";
        this.session.execute(q);
        this.session.execute("USE " + keyspaceName + ";");
    }

    private void _connect(int retries) {
        Cluster cluster = CLUSTERS.get(serversStr);
        boolean firstSession = false;

        Map<String, Session> sessions = null;
        try {
            if (cluster == null) {
                firstSession = true;
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
                        withLoadBalancingPolicy(latencyAware).
                        withQueryOptions(queryOpts).
                        withClusterName(clusterName).
                        withSocketOptions(sockOpts).build();

                SESSIONS.put(serversStr, new HashMap<String, Session>());
            }

            sessions = SESSIONS.get(serversStr);
            this.session = sessions.get(keyspaceName);

            if (this.session == null) {
                this.session = cluster.connect();
                createAndUseKeyspace();
                if (this.schema != null) {
                    for (int i=0; i<schema.length; i++)
                        this.session.executeAsync(schema[i]).get(10, TimeUnit.SECONDS);
                }
                sessions.put(keyspaceName, this.session);
                log.info("Connection to Cassandra key space {} ESTABLISHED", keyspaceName);
            }

            if (firstSession)
                CLUSTERS.put(serversStr, cluster);

            sessionPromise.success(session);

        } catch (Exception e) {
            log.error("Connection to Cassandra key space " + keyspaceName + " FAILED", e);
            if (this.session != null) {
                this.session.close();
                this.session = null;
                if (sessions != null)
                    sessions.remove(keyspaceName);
            }
            scheduleReconnect(retries);
        }
    }

    private void scheduleReconnect(final int retries) {
        log.info("Scheduling cassandra reconnection retry");
        if (reactor != null && retries > 0) {
            reactor.schedule(new Runnable() {
                @Override
                public void run() {
                    log.info("Trying to reconnect to cassandra");
                    synchronized (CLUSTERS) {
                        _connect(retries - 1);
                    }
                }
            }, 30, TimeUnit.SECONDS);
        } else if (reactor == null) {
            log.error("Permanently lost connection to cassandra and there is " +
                    "no reactor to schedule reconnects");
        } else {
            log.error("Unable to connect to cassandra after 10 retries, givin up");
        }
    }

    public Session session() {
        return session;
    }
}
