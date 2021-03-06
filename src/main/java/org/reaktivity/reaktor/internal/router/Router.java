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
package org.reaktivity.reaktor.internal.router;

import static org.reaktivity.reaktor.internal.router.RouteMatchers.authorizationMatches;
import static org.reaktivity.reaktor.internal.router.RouteMatchers.sourceMatches;
import static org.reaktivity.reaktor.internal.router.RouteMatchers.sourceRefMatches;
import static org.reaktivity.reaktor.internal.router.RouteMatchers.targetMatches;
import static org.reaktivity.reaktor.internal.router.RouteMatchers.targetRefMatches;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.Nukleus;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.reaktor.internal.Context;
import org.reaktivity.reaktor.internal.types.control.RouteFW;
import org.reaktivity.reaktor.internal.types.control.UnrouteFW;

public final class Router extends Nukleus.Composite
{
    private final RouteFW routeRO = new RouteFW();

    private final Set<RouteFW> routes;

    public Router(
        Context context)
    {
        this.routes = new LinkedHashSet<>();
    }

    @Override
    public String name()
    {
        return "router";
    }

    public boolean doRoute(
        RouteFW route,
        MessagePredicate routeHandler)
    {
        DirectBuffer srcBuffer = route.buffer();
        MutableDirectBuffer copyBuffer = new UnsafeBuffer(new byte[route.sizeof()]);
        copyBuffer.putBytes(0, srcBuffer, route.offset(), copyBuffer.capacity());
        RouteFW newRoute = new RouteFW().wrap(copyBuffer, 0, copyBuffer.capacity());

        return routeHandler.test(route.typeId(), route.buffer(), route.offset(), route.sizeof()) &&
               routes.add(newRoute);
    }

    public boolean doUnroute(
        UnrouteFW unroute,
        MessagePredicate routeHandler)
    {
        final String sourceName = unroute.source().asString();
        final long sourceRef = unroute.sourceRef();
        final String targetName = unroute.target().asString();
        final long targetRef = unroute.targetRef();
        final long authorization = unroute.authorization();

        final Predicate<RouteFW> filter =
                sourceMatches(sourceName)
                .and(sourceRefMatches(sourceRef))
                .and(targetMatches(targetName))
                .and(targetRefMatches(targetRef))
                .and(authorizationMatches(authorization));

        return routes.removeIf(filter) &&
               routeHandler.test(unroute.typeId(), unroute.buffer(), unroute.offset(), unroute.sizeof());
    }

    public <R> R resolve(
        final long authorization,
        MessagePredicate filter,
        MessageFunction<R> mapper)
    {
        R mapped = null;

        MessagePredicate secure = (m, b, i, l) ->
        {
            RouteFW route = routeRO.wrap(b, i, i + l);
            final long routeAuthorization = route.authorization();
            return (authorization & routeAuthorization) == routeAuthorization;
        };
        final MessagePredicate secureFilter = secure.and(filter);

        final Optional<RouteFW> optional = routes.stream()
              .filter(r -> secureFilter.test(r.typeId(), r.buffer(), r.offset(), r.limit()))
              .findFirst();

        if (optional.isPresent())
        {
            final RouteFW route = optional.get();

            mapped = mapper.apply(route.typeId(), route.buffer(), route.offset(), route.limit());
        }

        return mapped;
    }
}
