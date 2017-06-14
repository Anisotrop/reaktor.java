/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
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
package org.reaktivity.reaktor.internal;

import java.io.Closeable;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.reaktivity.nukleus.Configuration;
import org.reaktivity.nukleus.Nukleus;
import org.reaktivity.nukleus.NukleusBuilder;
import org.reaktivity.nukleus.route.RouteKind;
import org.reaktivity.nukleus.stream.StreamFactoryBuilder;
import org.reaktivity.reaktor.internal.acceptor.Acceptor;
import org.reaktivity.reaktor.internal.conductor.Conductor;
import org.reaktivity.reaktor.internal.router.Router;
import org.reaktivity.reaktor.internal.watcher.Watcher;

public class NukleusBuilderImpl implements NukleusBuilder
{
    private final Configuration config;
    private final String name;
    private final Map<RouteKind, StreamFactoryBuilder> streamFactoryBuilders;

    public NukleusBuilderImpl(
        Configuration config,
        String name)
    {
        this.config = config;
        this.name = name;
        this.streamFactoryBuilders = new EnumMap<>(RouteKind.class);
    }

    @Override
    public NukleusBuilder streamFactory(
        RouteKind kind,
        StreamFactoryBuilder builder)
    {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(builder, "supplier");

        this.streamFactoryBuilders.put(kind, builder);
        return this;
    }

    @Override
    public Nukleus build()
    {
        Context context = new Context();
        context.name(name).conclude(config);

        Conductor conductor = new Conductor(context);
        Watcher watcher = new Watcher(context);
        Router router = new Router(context);
        Acceptor acceptor = new Acceptor(context);

        conductor.setAcceptor(acceptor);
        watcher.setAcceptor(acceptor);
        acceptor.setConductor(conductor);
        acceptor.setRouter(router);
        acceptor.setStreamFactoryBuilder(streamFactoryBuilders::get);

        return new NukleusImpl(name, conductor, watcher, router, acceptor, context);
    }

    private static final class NukleusImpl extends Nukleus.Composite
    {
        private final String name;
        private final Closeable cleanup;

        NukleusImpl(
            String name,
            Conductor conductor,
            Watcher watcher,
            Router router,
            Acceptor acceptor,
            Closeable cleanup)
        {
            super(conductor, watcher, router, acceptor);
            this.name = name;
            this.cleanup = cleanup;
        }

        @Override
        public String name()
        {
            return name;
        }

        @Override
        public void close() throws Exception
        {
            super.close();
            cleanup.close();
        }
    }
}