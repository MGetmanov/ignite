/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.rest.handlers.redis.string;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.processors.rest.GridRestProtocolHandler;
import org.apache.ignite.internal.processors.rest.GridRestResponse;
import org.apache.ignite.internal.processors.rest.handlers.redis.GridRedisThruRestCommandHandler;
import org.apache.ignite.internal.processors.rest.protocols.tcp.redis.GridRedisCommand;
import org.apache.ignite.internal.processors.rest.protocols.tcp.redis.GridRedisMessage;
import org.apache.ignite.internal.processors.rest.protocols.tcp.redis.GridRedisProtocolParser;
import org.apache.ignite.internal.processors.rest.request.GridRestCacheRequest;
import org.apache.ignite.internal.processors.rest.request.GridRestRequest;
import org.apache.ignite.internal.util.typedef.internal.U;

import static org.apache.ignite.internal.processors.rest.GridRestCommand.CACHE_PUT;
import static org.apache.ignite.internal.processors.rest.GridRestCommand.CACHE_PUT_IF_ABSENT;
import static org.apache.ignite.internal.processors.rest.GridRestCommand.CACHE_REPLACE;
import static org.apache.ignite.internal.processors.rest.protocols.tcp.redis.GridRedisCommand.SET;

/**
 * Redis SET command handler.
 * <p>
 * No key expiration is currently supported.
 */
public class GridRedisSetCommandHandler extends GridRedisThruRestCommandHandler {
    /** Supported commands. */
    private static final Collection<GridRedisCommand> SUPPORTED_COMMANDS = U.sealList(
        SET
    );

    /** Value position in Redis message. */
    private static final int VAL_POS = 2;

    /** {@inheritDoc} */
    public GridRedisSetCommandHandler(final GridRestProtocolHandler hnd) {
        super(hnd);
    }

    /** {@inheritDoc} */
    @Override public Collection<GridRedisCommand> supportedCommands() {
        return SUPPORTED_COMMANDS;
    }

    /** {@inheritDoc} */
    @Override public GridRestRequest asRestRequest(GridRedisMessage msg) throws IgniteCheckedException {
        assert msg != null;

        GridRestCacheRequest restReq = new GridRestCacheRequest();

        restReq.clientId(msg.clientId());
        restReq.key(msg.key());

        restReq.command(CACHE_PUT);

        if (msg.messageSize() < 3)
            throw new IgniteCheckedException("Invalid request!");

        restReq.value(msg.aux(VAL_POS));

        if (msg.messageSize() >= 4) {

            List<String> params = msg.aux();

            // get rid of SET value.
            params.remove(0);

            if (isNx(params))
                restReq.command(CACHE_PUT_IF_ABSENT);
            else if (isXx(params))
                restReq.command(CACHE_REPLACE);

            // TODO: handle expiration options.
        }

        return restReq;
    }

    /**
     * @param params Command parameters.
     * @return True if NX option is available, otherwise false.
     */
    private boolean isNx(List<String> params) {
        if (params.size() >= 3)
            return params.get(0).equalsIgnoreCase("nx") || params.get(2).equalsIgnoreCase("nx");
        else
            return params.get(0).equalsIgnoreCase("nx");
    }

    /**
     * @param params Command parameters.
     * @return True if XX option is available, otherwise false.
     */
    private boolean isXx(List<String> params) {
        if (params.size() >= 3)
            return params.get(0).equalsIgnoreCase("xx") || params.get(2).equalsIgnoreCase("xx");
        else
            return params.get(0).equalsIgnoreCase("xx");
    }

    /** {@inheritDoc} */
    @Override public ByteBuffer makeResponse(final GridRestResponse restRes, List<String> params) {
        Object resp = restRes.getResponse();

        if (resp == null)
            return GridRedisProtocolParser.nil();

        return (!(boolean)resp ? GridRedisProtocolParser.nil() : GridRedisProtocolParser.OkString());
    }
}