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

import org.mongodb.annotations.NotThreadSafe;
import org.mongodb.io.ChannelAwareOutputBuffer;
import org.mongodb.io.ResponseBuffers;

import static org.mongodb.assertions.Assertions.isTrue;
import static org.mongodb.assertions.Assertions.notNull;

// TODO: should not be public
@NotThreadSafe
public class DelayedCloseConnection extends DelayedCloseBaseConnection implements Connection {
    private Connection wrapped;

    public DelayedCloseConnection(final Connection wrapped) {
        this.wrapped = notNull("wrapped", wrapped);
    }

    @Override
    public void sendMessage(final ChannelAwareOutputBuffer buffer) {
        isTrue("open", !isClosed());
        wrapped.sendAndReceiveMessage(buffer);
    }

    @Override
    public ResponseBuffers sendAndReceiveMessage(final ChannelAwareOutputBuffer buffer) {
        isTrue("open", !isClosed());
        return wrapped.sendAndReceiveMessage(buffer);
    }

    @Override
    public ResponseBuffers receiveMessage() {
        isTrue("open", !isClosed());
        return wrapped.receiveMessage();
    }

    @Override
    protected BaseConnection getWrapped() {
        return wrapped;
    }
}