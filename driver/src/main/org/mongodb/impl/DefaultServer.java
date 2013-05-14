/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.impl;

import org.mongodb.MongoAsyncConnectionFactory;
import org.mongodb.MongoClientOptions;
import org.mongodb.MongoException;
import org.mongodb.MongoSyncConnectionFactory;
import org.mongodb.Server;
import org.mongodb.ServerAddress;
import org.mongodb.async.SingleResultCallback;
import org.mongodb.command.IsMasterCommandResult;
import org.mongodb.io.BufferPool;
import org.mongodb.io.ChannelAwareOutputBuffer;
import org.mongodb.io.ResponseBuffers;
import org.mongodb.pool.Pool;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mongodb.assertions.Assertions.isTrue;
import static org.mongodb.assertions.Assertions.notNull;

public class DefaultServer implements Server {
    private final ScheduledExecutorService scheduledExecutorService;
    private ServerAddress serverAddress;
    private final Pool<Connection> connectionPool;
    private Pool<AsyncConnection> asyncConnectionPool;
    private final MongoIsMasterServerStateNotifier stateNotifier;
    private Set<MongoServerStateListener> changeListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<MongoServerStateListener, Boolean>());
    private volatile IsMasterCommandResult description;

    public DefaultServer(final ServerAddress serverAddress, final MongoSyncConnectionFactory connectionFactory,
                         final MongoAsyncConnectionFactory asyncConnectionFactory, final MongoClientOptions options,
                         final ScheduledExecutorService scheduledExecutorService, final BufferPool<ByteBuffer> bufferPool) {
        notNull("connectionFactor", connectionFactory);
        notNull("options", options);

        this.scheduledExecutorService = notNull("scheduledExecutorService", scheduledExecutorService);
        this.serverAddress = notNull("serverAddress", serverAddress);
        this.connectionPool = new DefaultMongoConnectionPool(connectionFactory, options);
        if (asyncConnectionFactory != null) {
            this.asyncConnectionPool = new DefaultMongoAsyncConnectionPool(asyncConnectionFactory, options);
        }
        stateNotifier = new MongoIsMasterServerStateNotifier(new DefaultMongoServerStateListener(), connectionFactory, bufferPool);
        scheduledExecutorService.scheduleAtFixedRate(stateNotifier, 0, 5000, TimeUnit.MILLISECONDS); // TODO: configurable
    }

    @Override
    public Connection getConnection() {
        return new DefaultServerSyncConnection(connectionPool.get());
    }

    @Override
    public AsyncConnection getAsyncConnection() {
        if (asyncConnectionPool == null) {
            throw new UnsupportedOperationException("Asynchronous connections not supported in this version of Java");
        }
        return new DefaultServerAsyncConnection(asyncConnectionPool.get());
    }

    @Override
    public ServerAddress getServerAddress() {
        return serverAddress;
    }

    public IsMasterCommandResult getDescription() {
        return description;
    }

    @Override
    public void addChangeListener(final MongoServerStateListener changeListener) {
        changeListeners.add(changeListener);
    }

    @Override
    public void invalidate() {
        description = null;
        scheduledExecutorService.submit(stateNotifier);
    }

    @Override
    public void close() {
        connectionPool.close();
        if (asyncConnectionPool != null) {
            asyncConnectionPool.close();
        }
        stateNotifier.close();
    }

    private void handleException() {
        invalidate();  // TODO: handle different exceptions sub-classes differently
    }

    private final class DefaultMongoServerStateListener implements MongoServerStateListener {
        @Override
        public void notify(final IsMasterCommandResult masterCommandResult) {
            description = masterCommandResult;
            for (MongoServerStateListener listener : changeListeners) {
                listener.notify(masterCommandResult);
            }
        }

        @Override
        public void notify(final MongoException e) {
            description = null;
            for (MongoServerStateListener listener : changeListeners) {
                listener.notify(e);
            }
        }
    }

    private class DefaultServerSyncConnection implements Connection {
        private Connection wrapped;

        public DefaultServerSyncConnection(final Connection wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public ServerAddress getServerAddress() {
            isTrue("open", !isClosed());
            return wrapped.getServerAddress();
        }

        @Override
        public void sendMessage(final ChannelAwareOutputBuffer buffer) {
            isTrue("open", !isClosed());
            try {
                wrapped.sendMessage(buffer);
            } catch (MongoException e) {
                handleException();
                throw e;
            }
        }

        @Override
        public ResponseBuffers sendAndReceiveMessage(final ChannelAwareOutputBuffer buffer) {
            isTrue("open", !isClosed());
            try {
                return wrapped.sendAndReceiveMessage(buffer);
            } catch (MongoException e) {
                handleException();
                throw e;
            }
        }

        @Override
        public ResponseBuffers receiveMessage() {
            isTrue("open", !isClosed());
            try {
                return wrapped.receiveMessage();
            } catch (MongoException e) {
                handleException();
                throw e;
            }
        }

        @Override
        public void close() {
            if (wrapped != null) {
                wrapped.close();
                wrapped = null;
            }
        }

        @Override
        public boolean isClosed() {
            return wrapped == null;
        }
    }

    // TODO: chain callbacks in order to be notified of exceptions
    private class DefaultServerAsyncConnection implements AsyncConnection {
        private AsyncConnection wrapped;

        public DefaultServerAsyncConnection(final AsyncConnection wrapped) {
            this.wrapped = notNull("wrapped", wrapped);
        }

        @Override
        public ServerAddress getServerAddress() {
            isTrue("open", !isClosed());
            return wrapped.getServerAddress();
        }

        @Override
        public void sendMessage(final ChannelAwareOutputBuffer buffer, final SingleResultCallback<ResponseBuffers> callback) {
            isTrue("open", !isClosed());
            wrapped.sendAndReceiveMessage(buffer, new InvalidatingSingleResultCallback(callback));
        }

        @Override
        public void sendAndReceiveMessage(final ChannelAwareOutputBuffer buffer, final SingleResultCallback<ResponseBuffers> callback) {
            isTrue("open", !isClosed());
            wrapped.sendAndReceiveMessage(buffer, new InvalidatingSingleResultCallback(callback));
        }

        @Override
        public void receiveMessage(final SingleResultCallback<ResponseBuffers> callback) {
            isTrue("open", !isClosed());
            wrapped.receiveMessage(new InvalidatingSingleResultCallback(callback));
        }

        @Override
        public void close() {
            if (wrapped != null) {
                wrapped.close();
                wrapped = null;
            }
        }

        @Override
        public boolean isClosed() {
            return wrapped == null;
        }


        private final class InvalidatingSingleResultCallback implements SingleResultCallback<ResponseBuffers> {
            private final SingleResultCallback<ResponseBuffers> callback;

            public InvalidatingSingleResultCallback(final SingleResultCallback<ResponseBuffers> callback) {
                this.callback = callback;
            }

            @Override
            public void onResult(final ResponseBuffers result, final MongoException e) {
                if (e != null) {
                    invalidate();
                }
                callback.onResult(result, e);
            }
        }
    }
}