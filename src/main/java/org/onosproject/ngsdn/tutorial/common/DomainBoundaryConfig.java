/*
 * Copyright 2019-present Open Networking Foundation
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

package org.onosproject.ngsdn.tutorial.common;

import com.fasterxml.jackson.databind.JsonNode;
import org.onlab.packet.Ip6Prefix;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Static cross-domain boundary routes generated from topology netcfg.
 */
public class DomainBoundaryConfig extends Config<ApplicationId> {

    public static final String CONFIG_KEY = "domainBoundaryConfig";

    private static final String DOMAIN = "domain";
    private static final String ROUTES = "routes";
    private static final String DEVICE = "device";
    private static final String PORT = "port";
    private static final String NEXT_HOP_MAC = "nextHopMac";
    private static final String PREFIXES = "prefixes";

    @Override
    public boolean isValid() {
        return hasOnlyFields(DOMAIN, ROUTES) &&
                (!hasField(ROUTES) || routes() != null);
    }

    /**
     * Gets configured domain name.
     *
     * @return domain name
     */
    public String domain() {
        return get(DOMAIN, null);
    }

    /**
     * Gets static boundary routes.
     *
     * @return boundary routes, or null if malformed
     */
    public List<BoundaryRoute> routes() {
        final JsonNode routesNode = object.path(ROUTES);
        final List<BoundaryRoute> routes = new ArrayList<>();
        if (routesNode.isMissingNode()) {
            return routes;
        }
        if (!routesNode.isArray()) {
            return null;
        }

        for (JsonNode routeNode : routesNode) {
            final BoundaryRoute route = parseRoute(routeNode);
            if (route == null) {
                return null;
            }
            routes.add(route);
        }
        return routes;
    }

    private BoundaryRoute parseRoute(JsonNode routeNode) {
        if (!routeNode.isObject() ||
                !routeNode.path(DEVICE).isTextual() ||
                !routeNode.path(PORT).canConvertToLong() ||
                !routeNode.path(NEXT_HOP_MAC).isTextual() ||
                !routeNode.path(PREFIXES).isArray()) {
            return null;
        }

        try {
            final List<Ip6Prefix> prefixes = new ArrayList<>();
            for (JsonNode prefixNode : routeNode.path(PREFIXES)) {
                if (!prefixNode.isTextual()) {
                    return null;
                }
                prefixes.add(Ip6Prefix.valueOf(prefixNode.asText()));
            }

            return new BoundaryRoute(
                    DeviceId.deviceId(routeNode.path(DEVICE).asText()),
                    PortNumber.portNumber(routeNode.path(PORT).asLong()),
                    MacAddress.valueOf(routeNode.path(NEXT_HOP_MAC).asText()),
                    prefixes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Static route via a cross-domain neighbor.
     */
    public static final class BoundaryRoute {
        private final DeviceId deviceId;
        private final PortNumber port;
        private final MacAddress nextHopMac;
        private final List<Ip6Prefix> prefixes;

        private BoundaryRoute(DeviceId deviceId, PortNumber port,
                              MacAddress nextHopMac, List<Ip6Prefix> prefixes) {
            this.deviceId = deviceId;
            this.port = port;
            this.nextHopMac = nextHopMac;
            this.prefixes = prefixes;
        }

        public DeviceId deviceId() {
            return deviceId;
        }

        public PortNumber port() {
            return port;
        }

        public MacAddress nextHopMac() {
            return nextHopMac;
        }

        public List<Ip6Prefix> prefixes() {
            return prefixes;
        }
    }
}
