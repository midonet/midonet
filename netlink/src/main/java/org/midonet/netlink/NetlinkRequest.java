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
package org.midonet.netlink;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.ErrorCode;
import org.midonet.netlink.exceptions.NetlinkException;

/** Class used by AbstractNetlinkRequest to manage reply handlers and user given
 *  callbacks. */
public abstract class NetlinkRequest implements Runnable {

    private static final Logger log =
        LoggerFactory.getLogger(NetlinkRequest.class);

    /** the State enum is used to track the internal state of the request. The
     *  possible transitions are NotYet -> Success -> HasRun or
     *  NotYet -> Failure -> HasRun. */
    enum State {
      NotYet,
      Success,
      Failure,
      HasRun;
    }

    private ByteBuffer outBuffer;

    // can be callback of T for single-answer requests, or callback of Set<T>
    // for multi-answer requests.
    private final Callback<Object> userCallback;
    protected final Reader<Object> reader;
    public final long expirationTimeNanos;
    public int seq;
    protected Object cbData = null;
    private State state = State.NotYet;

    private NetlinkRequest(Callback<Object> callback,
                          Reader<Object> reader,
                          ByteBuffer data,
                          long timeoutMillis) {
        this.userCallback = callback;
        this.reader = reader;
        this.outBuffer = data;
        this.expirationTimeNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    public boolean hasCallback() {
        return userCallback != null;
    }

    abstract public void addAnswerFragment(ByteBuffer buf);

    public ByteBuffer releaseRequestPayload() {
        ByteBuffer payload = outBuffer;
        outBuffer = null;
        return payload;
    }

    @Override
    public void run() {
        synchronized(this) {
            try {
                switch (state) {
                    case Success:
                        userCallback.onSuccess(cbData);
                        break;

                    case Failure:
                        @SuppressWarnings("unchecked")
                        NetlinkException e = (NetlinkException) cbData;
                        userCallback.onError(e);
                        break;

                    case NotYet:
                        throw new IllegalStateException(
                            "tried to run the user callback before " +
                            "completing the NetlinkRequest");

                    case HasRun:
                        throw new IllegalStateException(
                            "attempted to run the user callback " +
                            "of a NetlinkRequest more than once");
                }
            } catch (Exception e) {
                log.error("Error trying to run user callback", e);
            } finally {
                state = State.HasRun;
            }
        }
    }

    public Runnable successful() {
        changeState(State.Success, cbData);
        return this;
    }

    public Runnable failed(final NetlinkException e) {
        changeState(State.Failure, e);
        return this;
    }

    public Runnable expired() {
        ErrorCode er = ErrorCode.ETIMEOUT;
        String msg = "request #" + seq + " timeout";
        return failed(new NetlinkException(er, msg));
    }

    protected void changeState(State nextState, Object data) {
        synchronized(this) {
            if (state == State.NotYet) {
                cbData = data;
                state = nextState;
            } else {
                log.error("Attempted to create a runnable for the " +
                          "NetlinkRequest #{} more than once.", seq);
            }
        }
    }

    /** Factory method to create a NetlinkRequest which will be answered by a
     *  single reply, in which case the callback function takes as an input a
     *  single deserialised object. */
    public static <T> NetlinkRequest makeSingle(Callback<T> callback,
                                                Reader<T> reader,
                                                ByteBuffer data,
                                                long timeoutMillis) {
        @SuppressWarnings("unchecked")
        Callback<Object> cb = (Callback<Object>) callback;
        @SuppressWarnings("unchecked")
        Reader<Object> func = (Reader<Object>) reader;
        return new SingleAnswerNetlinkRequest(cb, func, data, timeoutMillis);
    }

    /** Factory method to create a NetlinkRequest which will be answered by a
     *  sequence of repies (enumerate requests), in which case the callback
     *  function takes as an input a set of deserialised objects. */
    public static <T> NetlinkRequest makeMulti(Callback<Set<T>> callback,
                                               Reader<T> reader,
                                               ByteBuffer data,
                                               long timeoutMillis) {

        @SuppressWarnings("unchecked")
        Callback<?> cb1 = (Callback<?>) callback;
        @SuppressWarnings("unchecked")
        Callback<Object> cb = (Callback<Object>) cb1;
        @SuppressWarnings("unchecked")
        Reader<Object> func = (Reader<Object>) reader;
        return new MultiAnswerNetlinkRequest(cb, func, data, timeoutMillis);
    }

    static class SingleAnswerNetlinkRequest extends NetlinkRequest {
        public SingleAnswerNetlinkRequest(Callback<Object> callback,
                                          Reader<Object> reader,
                                          ByteBuffer data,
                                          long timeoutMillis) {
            super(callback, reader, data, timeoutMillis);
        }
        @Override
        public void addAnswerFragment(ByteBuffer buf) {
            cbData = reader.deserializeFrom(buf);
        }
        @Override
        public Runnable successful() {
            // for requests with a flag ACK only (no ECHO), only an ACK reply is
            // read and addAnswerFragment() is not called by AbstractNetlinkCon.
            // This causes cbData to stay null, and in this case, we force a
            // call to the deserialisation function.
            if (cbData == null) {
                addAnswerFragment(null);
            }
            changeState(State.Success, cbData);
            return this;
        }
    }

    static class MultiAnswerNetlinkRequest extends NetlinkRequest {
        public MultiAnswerNetlinkRequest(Callback<Object> callback,
                                         Reader<Object> reader,
                                         ByteBuffer data,
                                         long timeoutMillis) {
            super(callback, reader, data, timeoutMillis);
            cbData = new HashSet<Object>();
        }
        @Override
        public void addAnswerFragment(ByteBuffer buf) {
            @SuppressWarnings("unchecked")
            HashSet<Object> results = (HashSet<Object>) cbData;
            results.add(reader.deserializeFrom(buf));
        }
    }

    // A null value is interpreted by the comparator as a netlinkrequest with
    // infinite timeout, and is therefore "larger" than any non-null request.
    public static final Comparator<NetlinkRequest> comparator =
        new Comparator<NetlinkRequest>() {
            @Override
            public int compare(NetlinkRequest a, NetlinkRequest b) {
                long aExp = a != null ? a.expirationTimeNanos : Long.MAX_VALUE;
                long bExp = b != null ? b.expirationTimeNanos : Long.MAX_VALUE;
                return a == b ? 0 : Long.compare(aExp, bExp);
            }

            @Override
            public boolean equals(Object o) {
                return o == this;
            }
    };
}
