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

package org.midonet.cluster.services.topology.common

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicReference, AtomicBoolean}

import scala.concurrent.{ExecutionContext, Promise, Future}

import com.google.protobuf.Message
import io.netty.channel.{ChannelFuture, ChannelHandlerContext}
import io.netty.util.concurrent.GenericFutureListener
import org.slf4j.LoggerFactory
import rx.Observer


/**
 * Connection state holder
 * Note: this class exposes an observer (a subject) to receive the
 * messages that should be sent back through the associated
 * low-level communication channel.
 * @param ctx is the low level communication channel
 * @param protocol is the factory generating the start state for the
 *                 communication protocol
 * @param mgr is the ConnectionManager responsible for this particular
 *            connection (used to unregister itself when the low level
 *            communication channel is severed)
 */
class Connection(private val ctx: ChannelHandlerContext,
                 private val protocol: ProtocolFactory,
                 private val senderFactory: MessageSenderFactory
                    = MessageSender)
                (implicit val mgr: ConnectionManager)
    extends Observer[Message] {
    private val log = LoggerFactory.getLogger(classOf[Connection])
    private val sender: MessageSender = senderFactory.get(ctx)
    private implicit val ec = sender.getWriteExecutionContext

    // Connection has already been disconnected
    private val terminated = new AtomicBoolean(false)

    // Keep track of last message, to make sure the pipeline is cleared before
    // completion
    private val lastSent =
        new AtomicReference[Future[Boolean]](Future.successful(true))

    // Send a message through the low level channel
    private def send(rsp: Message) = if (!terminated.get()) {
        log.debug("outgoing msg: " + rsp)
        lastSent.set(sender.sendAndFlush(rsp))
    } else {
        log.debug("discarded msg after disconnect: " + rsp)
    }

    // Terminate this connection
    private def terminate() = if (terminated.compareAndSet(false, true)) {
        log.debug("connection terminated")
        ctx.close()
        mgr.unregister(ctx)
    }

    // Process the messages on the outgoing stream
    override def onCompleted(): Unit =
        lastSent.get.onComplete({case _ => terminate()})
    override def onError(e: Throwable): Unit =
        lastSent.get.onComplete({case _ => terminate()})
    override def onNext(rsp: Message): Unit = send(rsp)

    // State engine
    // NOTE: This is not thread-safe, which is currently fine
    // as each channel is currently handled by a single thread.
    private var state = protocol.start(this)

    /**
     * Dismiss the connection state (called upon netty disconnection)
     * NOTE: the protocol must complete the observable on Interruption
     */
    def disconnect() = {
        state = state.process(Interruption)
    }

    /**
     * Process a protobuf message received from netty
     * @param req is a protobuf message encoding either a request or a
     *            response in a given protocol. Note that it is not safe
     *            to keep this protobuf for future use without increasing
     *            its reference counter via 'retain' (and releasing for it
     *            when no longer needed via 'ReferenceCountUtils.release'
     */
    def msg(req: Message) = {
        log.debug("incoming message: " + req)
        state = state.process(req)
    }

    /**
     * Process exceptions originated in the netty pipeline (normally they
     * are not recoverable, but the high level protocol may want to take
     * some action).
     * @param e the captured exception
     */
    def error(e: Throwable) = {
        log.debug("incoming exception", e)
        state = state.process(e)
    }
}

/**
 * A class to guarantee that writes to a given netty context are sent one
 * by one, to avoid concurrency issues. Note that sending stops when an
 * error is encountered: all subsequent sends will be cancelled
 * @param ctx is the netty connection context
 * @param start is a future that must be completed before starting data writes
 */
class MessageSender(val ctx: ChannelHandlerContext,
                    val start: Future[Boolean] = Future.successful(true),
                    val writeExecutor: ExecutionContext =
                    MessageSender.getWriteExecutionContext) {

    private implicit val ec = writeExecutor
    private val log = LoggerFactory.getLogger(classOf[MessageSender])
    private val lastOp = new AtomicReference[Future[Boolean]](start)

    def getWriteExecutionContext: ExecutionContext = ec
    def ready: Future[Boolean] = lastOp.get()

    /**
     * Send a message guaranteeing that no other write is on the fly
     * @param msg is the message to send
     * @return a future that completes when the write is actually done
     */
    def send(msg: Message, flush: Boolean = false): Future[Boolean] =
        doWhenReady({sendNow(msg, _, flush)})
    def sendAndFlush(msg: Message): Future[Boolean] = send(msg, flush = true)
    def flush() :Future[Boolean] = doWhenReady({flushNow(_)})

    private def doWhenReady(op: Promise[Boolean] => Unit): Future[Boolean] = {
        val done = Promise[Boolean]()
        val previous = lastOp.getAndSet(done.future)
        previous.onSuccess({case _ => op(done)})
        previous.onFailure({case err => done.failure(err)})
        done.future
    }

    /**
     * Send a message right now, without checking if other writes are on the fly
     * @param msg is the message to send
     * @param done is the promise to be completed when the write is done
     * @return a future that completes when the write is done
     */
    def sendNow(msg: Message, done: Promise[Boolean] = Promise[Boolean](),
                flush: Boolean = false)
        : Future[Boolean] = {
        log.debug("sending message: " + msg)
        val future: ChannelFuture =
            if (flush) ctx.writeAndFlush(msg) else ctx.write(msg)
        future.addListener(new GenericFutureListener[ChannelFuture] {
            override def operationComplete(f: ChannelFuture): Unit = {
                if (f.isSuccess) {
                    log.debug("sent message: " + msg)
                    done.success(true)
                } else if (f.isCancelled) {
                    log.debug("canceled message: " + msg)
                    done.success(false)
                } else {
                    log.debug("failed message: " + msg, f.cause)
                    done.failure(f.cause)
                }
            }
        })
        done.future
    }

    def sendAndFlushNow(msg: Message,
                        done: Promise[Boolean] = Promise[Boolean]())
        : Future[Boolean] = sendNow(msg, done, flush = true)

    def flushNow(done: Promise[Boolean] = Promise[Boolean]())
        : Future[Boolean] = try {
        ctx.flush()
        done.success(true).future
    } catch {
        case e: Throwable => done.failure(e).future
    }
}

object MessageSender extends MessageSenderFactory {
    // Use a single thread for all writes (for any context)
    private lazy val writeExecutor = Executors.newSingleThreadExecutor()
    private lazy val writeExecutionContext =
        ExecutionContext.fromExecutorService(writeExecutor)
    def getWriteExecutionContext: ExecutionContext = writeExecutionContext

    // Factory method to make testing easier
    def get(ctx: ChannelHandlerContext,
            start: Future[Boolean] = Future.successful(true))
        : MessageSender =
        new MessageSender(ctx, start, getWriteExecutionContext)
}

// Factory class for easy testing
trait MessageSenderFactory {
    def get(ctx: ChannelHandlerContext,
            start: Future[Boolean] = Future.successful(true))
        : MessageSender
}

