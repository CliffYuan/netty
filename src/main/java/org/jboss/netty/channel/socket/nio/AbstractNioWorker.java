/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.netty.channel.socket.nio;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.socket.Worker;
import org.jboss.netty.channel.socket.nio.SocketSendBufferPool.SendBuffer;
import org.jboss.netty.logging.XndLogger;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.jboss.netty.channel.Channels.*;

abstract class AbstractNioWorker extends AbstractNioSelector implements Worker {

    protected final SocketSendBufferPool sendBufferPool = new SocketSendBufferPool();

    AbstractNioWorker(Executor executor) {
        super(executor);
    }

    AbstractNioWorker(Executor executor, ThreadNameDeterminer determiner) {
        super(executor, determiner);

    }

    public void executeInIoThread(Runnable task) {
        executeInIoThread(task, false);
    }

    /**
     * Execute the {@link Runnable} in a IO-Thread
     *
     * @param task
     *            the {@link Runnable} to execute
     * @param alwaysAsync
     *            {@code true} if the {@link Runnable} should be executed
     *            in an async fashion even if the current Thread == IO Thread
     */
    public void executeInIoThread(Runnable task, boolean alwaysAsync) {
        if (!alwaysAsync && isIoThread()) {
            task.run();
        } else {
            registerTask(task);
        }
    }

    @Override
    protected void close(SelectionKey k) {
        AbstractNioChannel<?> ch = (AbstractNioChannel<?>) k.attachment();
        close(ch, succeededFuture(ch));
    }

    @Override
    protected ThreadRenamingRunnable newThreadRenamingRunnable(int id, ThreadNameDeterminer determiner) {
        return new ThreadRenamingRunnable(this, "New I/O worker #" + id, determiner);
    }

    @Override
    public void run() {
        super.run();
        sendBufferPool.releaseExternalResources();
    }

    @Override
    protected void process(Selector selector) throws IOException {
        XndLogger.process("AbstractNioWorker.process() 处理Selector到的selectedKeys开始，主要是read和write");
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            XndLogger.process("AbstractNioWorker.process() 处理Selector到的selectedKeys开始，主要是read和write,没有待处理的SelectionKey");
            return;
        }
        for (Iterator<SelectionKey> i = selectedKeys.iterator(); i.hasNext();) {
            SelectionKey k = i.next();
            i.remove();
            try {
                int readyOps = k.readyOps();
                if ((readyOps & SelectionKey.OP_READ) != 0 || readyOps == 0) {
                    XndLogger.process("AbstractNioWorker.process() 处理Selector到的selectedKeys,读数据SelectionKey="+k);
                    if (!read(k)) {
                        // Connection already closed - no need to handle write.
                        continue;
                    }
                }
                if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                    XndLogger.process("AbstractNioWorker.process() 处理Selector到的selectedKeys,写数据SelectionKey="+k);
                    writeFromSelectorLoop(k);
                }
            } catch (CancelledKeyException e) {
                close(k);
            }

            if (cleanUpCancelledKeys()) {
                break; // break the loop to avoid ConcurrentModificationException
            }
        }
    }

    void writeFromUserCode(final AbstractNioChannel<?> channel) {
        if (!channel.isConnected()) {
            cleanUpWriteBuffer(channel);
            return;
        }

        if (scheduleWriteIfNecessary(channel)) { //通过writeTask最后调用下面的writeFromTaskLoop（）
            return;
        }

        // From here, we are sure Thread.currentThread() == workerThread. 当前线程就是work io线程

        if (channel.writeSuspended) {
            return;
        }

        if (channel.inWriteNowLoop) {
            return;
        }

        write0(channel);
    }

    /**
     * nio worker 线程调用处理
     * 处理注册的task
     * @param ch
     */
    void writeFromTaskLoop(AbstractNioChannel<?> ch) {
        if (!ch.writeSuspended) {
            write0(ch);
        }
    }

    /**
     * nio worker 线程调用处理
     * 前提是：1.注册了write事件。
     *         2.有待写的缓存空间
     * @param k
     */
    void writeFromSelectorLoop(final SelectionKey k) {
        AbstractNioChannel<?> ch = (AbstractNioChannel<?>) k.attachment();
        ch.writeSuspended = false;
        write0(ch);
    }

    protected abstract boolean scheduleWriteIfNecessary(AbstractNioChannel<?> channel);

    /**
     * 当一次性没有写完，则注册OP_WRITE事件
     *
     * @param channel
     */
    protected void write0(AbstractNioChannel<?> channel) {
        boolean open = true;
        boolean addOpWrite = false;//添加写事件标示
        boolean removeOpWrite = false;
        boolean iothread = isIoThread(channel);

        long writtenBytes = 0;

        final SocketSendBufferPool sendBufferPool = this.sendBufferPool;
        final WritableByteChannel ch = channel.channel;
        final Queue<MessageEvent> writeBuffer = channel.writeBufferQueue;
        final int writeSpinCount = channel.getConfig().getWriteSpinCount();
        List<Throwable> causes = null;

        synchronized (channel.writeLock) {
            channel.inWriteNowLoop = true;
            for (;;) {

                MessageEvent evt = channel.currentWriteEvent;//上一次写完了，这个对象会制空，见221行,第一次也是null
                SendBuffer buf = null;
                ChannelFuture future = null;
                try {
                    if (evt == null) {
                        if ((channel.currentWriteEvent = evt = writeBuffer.poll()) == null) {
                            removeOpWrite = true;
                            channel.writeSuspended = false;
                            break;
                        }
                        future = evt.getFuture();

                        channel.currentWriteBuffer = buf = sendBufferPool.acquire(evt.getMessage());//构造发送buf对象
                    } else {
                        future = evt.getFuture();
                        buf = channel.currentWriteBuffer;
                    }

                    long localWrittenBytes = 0;
                    for (int i = writeSpinCount; i > 0; i --) {//因nonblocking ,需要循环写，直到全部write完成
                        localWrittenBytes = buf.transferTo(ch);//其实是UnpooledSendBuffer.transferTo(SocketChannel)
                        if (localWrittenBytes != 0) {
                            writtenBytes += localWrittenBytes;
                            break;
                        }
                        if (buf.finished()) {
                            break;
                        }
                    }

                    if (buf.finished()) {
                        // Successful write - proceed to the next message.
                        buf.release();
                        channel.currentWriteEvent = null;
                        channel.currentWriteBuffer = null;
                        // Mark the event object for garbage collection.
                        //noinspection UnusedAssignment
                        evt = null;
                        buf = null;
                        future.setSuccess();
                    } else {
                        // Not written fully - perhaps the kernel buffer is full.
                        addOpWrite = true;//没有写完，则添加write事件.............................待缓冲区空了触发写
                        channel.writeSuspended = true;

                        if (writtenBytes > 0) {
                            // Notify progress listeners if necessary.
                            future.setProgress(
                                    localWrittenBytes,
                                    buf.writtenBytes(), buf.totalBytes());
                        }
                        break;
                    }
                } catch (AsynchronousCloseException e) {
                    // Doesn't need a user attention - ignore.
                } catch (Throwable t) {
                    if (buf != null) {
                        buf.release();
                    }
                    channel.currentWriteEvent = null;
                    channel.currentWriteBuffer = null;
                    // Mark the event object for garbage collection.
                    //noinspection UnusedAssignment
                    buf = null;
                    //noinspection UnusedAssignment
                    evt = null;
                    if (future != null) {
                        future.setFailure(t);
                    }
                    if (iothread) {
                        // An exception was thrown from within a write in the iothread. We store a reference to it
                        // in a list for now and notify the handlers in the chain after the writeLock was released
                        // to prevent possible deadlock.
                        // See #1310
                        if (causes == null) {
                            causes = new ArrayList<Throwable>(1);
                        }
                        causes.add(t);
                    } else {
                        fireExceptionCaughtLater(channel, t);
                    }
                    if (t instanceof IOException) {
                        // close must be handled from outside the write lock to fix a possible deadlock
                        // which can happen when MemoryAwareThreadPoolExecutor is used and the limit is exceed
                        // and a close is triggered while the lock is hold. This is because the close(..)
                        // may try to submit a task to handle it via the ExecutorHandler which then deadlocks.
                        // See #1310
                        open = false;
                    }
                }
            }
            channel.inWriteNowLoop = false;

            // Initially, the following block was executed after releasing
            // the writeLock, but there was a race condition, and it has to be
            // executed before releasing the writeLock:
            //
            //     https://issues.jboss.org/browse/NETTY-410
            //
            if (open) {
                if (addOpWrite) {
                    setOpWrite(channel);//注册写事件
                } else if (removeOpWrite) {
                    clearOpWrite(channel);
                }
            }
        }
        if (causes != null) {
            for (Throwable cause: causes) {
                // notify about cause now as it was triggered in the write loop
                fireExceptionCaught(channel, cause);
            }
        }
        if (!open) {
            // close the channel now
            close(channel, succeededFuture(channel));
        }
        if (iothread) {
            fireWriteComplete(channel, writtenBytes);
        } else {
            fireWriteCompleteLater(channel, writtenBytes);
        }
    }

    static boolean isIoThread(AbstractNioChannel<?> channel) {
        return Thread.currentThread() == channel.worker.thread;
    }

    protected void setOpWrite(AbstractNioChannel<?> channel) {
        Selector selector = this.selector;
        SelectionKey key = channel.channel.keyFor(selector);
        if (key == null) {
            return;
        }
        if (!key.isValid()) {
            close(key);
            return;
        }

        int interestOps = channel.getRawInterestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            interestOps |= SelectionKey.OP_WRITE;
            key.interestOps(interestOps);
            channel.setRawInterestOpsNow(interestOps);
        }
    }

    protected void clearOpWrite(AbstractNioChannel<?> channel) {
        Selector selector = this.selector;
        SelectionKey key = channel.channel.keyFor(selector);
        if (key == null) {
            return;
        }
        if (!key.isValid()) {
            close(key);
            return;
        }

        int interestOps = channel.getRawInterestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            interestOps &= ~SelectionKey.OP_WRITE;
            key.interestOps(interestOps);
            channel.setRawInterestOpsNow(interestOps);
        }
    }

    protected void close(AbstractNioChannel<?> channel, ChannelFuture future) {
        boolean connected = channel.isConnected();
        boolean bound = channel.isBound();
        boolean iothread = isIoThread(channel);

        try {
            channel.channel.close();
            increaseCancelledKeys();

            if (channel.setClosed()) {
                future.setSuccess();
                if (connected) {
                    if (iothread) {
                        fireChannelDisconnected(channel);
                    } else {
                        fireChannelDisconnectedLater(channel);
                    }
                }
                if (bound) {
                    if (iothread) {
                        fireChannelUnbound(channel);
                    } else {
                        fireChannelUnboundLater(channel);
                    }
                }

                cleanUpWriteBuffer(channel);
                if (iothread) {
                    fireChannelClosed(channel);
                } else {
                    fireChannelClosedLater(channel);
                }
            } else {
                future.setSuccess();
            }
        } catch (Throwable t) {
            future.setFailure(t);
            if (iothread) {
                fireExceptionCaught(channel, t);
            } else {
                fireExceptionCaughtLater(channel, t);
            }
        }
    }

    protected static void cleanUpWriteBuffer(AbstractNioChannel<?> channel) {
        Exception cause = null;
        boolean fireExceptionCaught = false;

        // Clean up the stale messages in the write buffer.
        synchronized (channel.writeLock) {
            MessageEvent evt = channel.currentWriteEvent;
            if (evt != null) {
                // Create the exception only once to avoid the excessive overhead
                // caused by fillStackTrace.
                if (channel.isOpen()) {
                    cause = new NotYetConnectedException();
                } else {
                    cause = new ClosedChannelException();
                }

                ChannelFuture future = evt.getFuture();
                if (channel.currentWriteBuffer != null) {
                    channel.currentWriteBuffer.release();
                    channel.currentWriteBuffer = null;
                }
                channel.currentWriteEvent = null;
                // Mark the event object for garbage collection.
                //noinspection UnusedAssignment
                evt = null;
                future.setFailure(cause);
                fireExceptionCaught = true;
            }

            Queue<MessageEvent> writeBuffer = channel.writeBufferQueue;
            for (;;) {
                evt = writeBuffer.poll();
                if (evt == null) {
                    break;
                }
                // Create the exception only once to avoid the excessive overhead
                // caused by fillStackTrace.
                if (cause == null) {
                    if (channel.isOpen()) {
                        cause = new NotYetConnectedException();
                    } else {
                        cause = new ClosedChannelException();
                    }
                    fireExceptionCaught = true;
                }
                evt.getFuture().setFailure(cause);
            }
        }

        if (fireExceptionCaught) {
            if (isIoThread(channel)) {
                fireExceptionCaught(channel, cause);
            } else {
                fireExceptionCaughtLater(channel, cause);
            }
        }
    }

    void setInterestOps(final AbstractNioChannel<?> channel, final ChannelFuture future, final int interestOps) {
        boolean iothread = isIoThread(channel);
        if (!iothread) {
            channel.getPipeline().execute(new Runnable() {
                public void run() {
                    setInterestOps(channel, future, interestOps);
                }
            });
            return;
        }

        boolean changed = false;
        try {
            Selector selector = this.selector;
            SelectionKey key = channel.channel.keyFor(selector);

            // Override OP_WRITE flag - a user cannot change this flag.
            int newInterestOps = interestOps & ~Channel.OP_WRITE | channel.getRawInterestOps() & Channel.OP_WRITE;

            if (key == null || selector == null) {
                if (channel.getRawInterestOps() != newInterestOps) {
                    changed = true;
                }

                // Not registered to the worker yet.
                // Set the rawInterestOps immediately; RegisterTask will pick it up.
                channel.setRawInterestOpsNow(newInterestOps);

                future.setSuccess();
                if (changed) {
                    if (iothread) {
                        fireChannelInterestChanged(channel);
                    } else {
                        fireChannelInterestChangedLater(channel);
                    }
                }

                return;
            }

            if (channel.getRawInterestOps() != newInterestOps) {
                changed = true;
                key.interestOps(newInterestOps);
                if (Thread.currentThread() != thread &&
                    wakenUp.compareAndSet(false, true)) {
                    selector.wakeup();
                }
                channel.setRawInterestOpsNow(newInterestOps);
            }

            future.setSuccess();
            if (changed) {
                fireChannelInterestChanged(channel);
            }
        } catch (CancelledKeyException e) {
            // setInterestOps() was called on a closed channel.
            ClosedChannelException cce = new ClosedChannelException();
            future.setFailure(cce);
            fireExceptionCaught(channel, cce);
        } catch (Throwable t) {
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        }
    }

    /**
     * Read is called when a Selector has been notified that the underlying channel
     * was something to be read. The channel would previously have registered its interest
     * in read operations.
     *
     * @param k The selection key which contains the Selector registration information.
     */
    protected abstract boolean read(SelectionKey k);

}
