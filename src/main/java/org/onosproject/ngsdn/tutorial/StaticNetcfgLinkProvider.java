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

package org.onosproject.ngsdn.tutorial;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.LinkKey;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.link.DefaultLinkDescription;
import org.onosproject.net.link.LinkDescription;
import org.onosproject.net.link.LinkProvider;
import org.onosproject.net.link.LinkProviderRegistry;
import org.onosproject.net.link.LinkProviderService;
import org.onosproject.net.provider.AbstractProvider;
import org.onosproject.net.provider.ProviderId;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.onosproject.ngsdn.tutorial.AppConstants.APP_NAME;
import static org.onosproject.ngsdn.tutorial.AppConstants.INITIAL_SETUP_DELAY;

/**
 * Publishes netcfg link subjects to the ONOS link store.
 *
 * <p>ONOS' built-in netcfg link provider uses configured links to constrain
 * LLDP discovery. In BMv2/P4Runtime labs, LLDP can be unavailable even though
 * netcfg already contains the complete topology. This provider makes those
 * configured links visible to the topology service and web UI.</p>
 */
@Component(
        immediate = true,
        enabled = true
)
public class StaticNetcfgLinkProvider extends AbstractProvider implements LinkProvider {

    private static final Logger log =
            LoggerFactory.getLogger(StaticNetcfgLinkProvider.class);

    private static final String PROVIDER_SCHEME = "static";

    private final NetworkConfigListener configListener = new InternalConfigListener();
    private final DeviceListener deviceListener = new InternalDeviceListener();
    private final Set<LinkKey> publishedLinks = new HashSet<>();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private LinkProviderRegistry linkProviderRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private NetworkConfigService networkConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MainComponent mainComponent;

    private LinkProviderService providerService;

    public StaticNetcfgLinkProvider() {
        super(new ProviderId(PROVIDER_SCHEME, APP_NAME + ".netcfg-links"));
    }

    @Activate
    protected void activate() {
        providerService = linkProviderRegistry.register(this);
        networkConfigService.addListener(configListener);
        deviceService.addListener(deviceListener);

        mainComponent.scheduleTask(this::syncConfiguredLinks, INITIAL_SETUP_DELAY);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        networkConfigService.removeListener(configListener);
        deviceService.removeListener(deviceListener);
        withdrawAllLinks();
        linkProviderRegistry.unregister(this);
        providerService = null;

        log.info("Stopped");
    }

    private synchronized void syncConfiguredLinks() {
        if (providerService == null) {
            return;
        }

        final Set<LinkKey> desiredLinks = networkConfigService.getSubjects(LinkKey.class)
                .stream()
                .filter(this::hasAvailableEndpoints)
                .collect(Collectors.toSet());

        final Set<LinkKey> staleLinks = new HashSet<>(publishedLinks);
        staleLinks.removeAll(desiredLinks);
        staleLinks.forEach(this::withdrawLink);

        desiredLinks.forEach(this::publishLink);

        publishedLinks.clear();
        publishedLinks.addAll(desiredLinks);

        if (!desiredLinks.isEmpty() || !staleLinks.isEmpty()) {
            log.info("Synchronized {} static netcfg link(s), removed {} stale link(s)",
                    desiredLinks.size(), staleLinks.size());
        }
    }

    private synchronized void withdrawAllLinks() {
        if (providerService == null) {
            return;
        }
        new HashSet<>(publishedLinks).forEach(this::withdrawLink);
        publishedLinks.clear();
    }

    private boolean hasAvailableEndpoints(LinkKey link) {
        return isAvailable(link.src()) && isAvailable(link.dst());
    }

    private boolean isAvailable(ConnectPoint connectPoint) {
        final DeviceId deviceId = connectPoint.deviceId();
        return deviceId != null && deviceService.isAvailable(deviceId);
    }

    private void publishLink(LinkKey link) {
        providerService.linkDetected(description(link));
    }

    private void withdrawLink(LinkKey link) {
        providerService.linkVanished(description(link));
    }

    private LinkDescription description(LinkKey link) {
        return new DefaultLinkDescription(
                link.src(), link.dst(), Link.Type.DIRECT, DefaultAnnotations.EMPTY);
    }

    private void scheduleSync() {
        mainComponent.getExecutorService().execute(this::syncConfiguredLinks);
    }

    private class InternalConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            switch (event.type()) {
                case CONFIG_ADDED:
                case CONFIG_UPDATED:
                case CONFIG_REMOVED:
                    if (event.subject() instanceof LinkKey) {
                        scheduleSync();
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private class InternalDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_ADDED:
                case DEVICE_AVAILABILITY_CHANGED:
                case DEVICE_REMOVED:
                    scheduleSync();
                    break;
                default:
                    break;
            }
        }
    }
}
