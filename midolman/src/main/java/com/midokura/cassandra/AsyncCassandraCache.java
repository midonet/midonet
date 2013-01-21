// Copyright 2012 Midokura Inc.

package com.midokura.cassandra;

import java.nio.ByteBuffer;
import java.io.IOException;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.NotFoundException;
import org.apache.cassandra.thrift.TimedOutException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.async.TAsyncClientManager;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TNonblockingSocket;
import org.apache.thrift.transport.TNonblockingTransport;

import com.midokura.util.functors.Callback1;


class AsyncCassandraCache {
    private TAsyncClientManager clientManager;
    private TProtocolFactory protocolFactory;
    private TNonblockingTransport transport;
    private ColumnParent columnParent;
    private String columnFamily;

    public AsyncCassandraCache(String hostIP, int port, String columnFamilyName)
            throws IOException {
        // TODO(jlm): Provide failover.  Possibly use
        // http://www.dataforte.net/software/cassandra-connection-pool/index.html
        clientManager = new TAsyncClientManager();
        protocolFactory = new TBinaryProtocol.Factory();
        transport = new TNonblockingSocket(hostIP, port);
        columnParent = new ColumnParent(columnFamilyName);
        columnFamily = columnFamilyName;
    }

    public void set(String key, String value) {
        // TODO(jlm): Using 'key' for both the column name and the row key.
        // Is this correct?
        // TODO(jlm): Set column TTL
        Column c = new Column();
        c.setName(key.getBytes());
        c.setValue(value.getBytes());
        c.setTimestamp(System.currentTimeMillis());
        Cassandra.AsyncClient client = getClient();
        try {
            client.insert(ByteBuffer.wrap(key.getBytes()), columnParent, c,
                          ConsistencyLevel.QUORUM, new InsertCallback(client));
        } catch (TException e) {
            //XXX: log, possibly retry.
        }
    }

    public void get(String key, Callback1<String> cb) {
        ColumnPath cp = new ColumnPath(columnFamily);
        Cassandra.AsyncClient client = getClient();
        try {
            client.get(ByteBuffer.wrap(key.getBytes()), cp,
                       ConsistencyLevel.QUORUM, new GetCallback(client, cb));
        } catch (TException e) {
            //XXX: log, possibly retry.
        }
    }

    private Cassandra.AsyncClient getClient() {
        return null;    //XXX
    }

    private class InsertCallback extends ClientReleaser
            implements AsyncMethodCallback<Cassandra.AsyncClient.insert_call> {
        public InsertCallback(Cassandra.AsyncClient client_) {
            client = client_;
        }

        @Override
        public void onComplete(Cassandra.AsyncClient.insert_call response) {
            releaseClient();
        }

        @Override
        public void onError(Exception e) {
            releaseClient();
            // XXX: log the error
            // XXX: if recoverable, retry with new client
        }
    }

    private class GetCallback extends ClientReleaser
            implements AsyncMethodCallback<Cassandra.AsyncClient.get_call> {
        private Callback1<String> cb;

        public GetCallback(Cassandra.AsyncClient client_,
                           Callback1<String> cb_) {
            client = client_;
            cb = cb_;
        }

        @Override
        public void onComplete(Cassandra.AsyncClient.get_call response) {
            Column col;
            try {
                col = response.getResult().getColumn();
            } catch (InvalidRequestException e) {
                //XXX: log.  Possibly rethrow.
                cb.call(null);
                return;
            } catch (NotFoundException e) {
                // TODO: Should "not found" be distinguished from "error"?
                cb.call(null);
                return;
            } catch (UnavailableException e) {
                // XXX: log, retry.
                return;
            } catch (TimedOutException e) {
                //XXX: log.  Retry or give up.
                return;
            } catch (TException e) {
                // Uninformative cassandra excetion.  Boo.
                //XXX: log.  Retry or give up.
                return;
            } finally {
                releaseClient();
            }
            cb.call(new String(col.getValue()));
        }

        @Override
        public void onError(Exception e) {
            releaseClient();
            // XXX: log the error
            // XXX: if recoverable, retry with new client
        }
    }

    private class ClientReleaser {
        protected Cassandra.AsyncClient client;

        protected void releaseClient() {
            // XXX
        }
    }
}
