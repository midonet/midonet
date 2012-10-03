// Copyright 2012 Midokura Inc.

package com.midokura.cassandra;

import java.io.IOException;

import org.apache.thrift.async.TAsyncClientManager;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TNonblockingSocket;
import org.apache.thrift.transport.TNonblockingTransport;


class AsyncCassandraCache {
    private TAsyncClientManager clientManager;
    private TProtocolFactory protocolFactory;
    private TNonblockingTransport transport;
 
    public AsyncCassandraCache(String hostIP, int port) throws IOException {
        clientManager = new TAsyncClientManager();
        protocolFactory = new TBinaryProtocol.Factory();
        transport = new TNonblockingSocket(hostIP, port);
    }

}
