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

import org.onlab.util.SharedScheduledExecutors;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.LinkKey;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.config.basics.BasicDeviceConfig;
import org.onosproject.net.device.DefaultPortDescription;
import org.onosproject.net.device.DefaultPortStatistics;
import org.onosproject.net.device.DeviceProvider;
import org.onosproject.net.device.DeviceProviderRegistry;
import org.onosproject.net.device.DeviceProviderService;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortDescription;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.pi.model.PiCounterId;
import org.onosproject.net.pi.model.PiPipeconf;
import org.onosproject.net.pi.runtime.PiCounterCell;
import org.onosproject.net.pi.service.PiPipeconfService;
import org.onosproject.net.provider.AbstractProvider;
import org.onosproject.net.provider.ProviderId;
import org.onosproject.p4runtime.api.P4RuntimeClient;
import org.onosproject.p4runtime.api.P4RuntimeController;
import org.onosproject.p4runtime.api.P4RuntimeReadClient;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.onosproject.ngsdn.tutorial.AppConstants.CPU_PORT_ID;
import static org.onosproject.ngsdn.tutorial.AppConstants.INITIAL_SETUP_DELAY;

/**
 * Publishes BMv2 P4Runtime counters as ONOS port statistics.
 *
 * <p>The BMv2/P4Runtime driver used in this lab does not expose native port
 * statistics. This provider publishes the netcfg-defined BMv2 ports, reads the
 * P4 per-port ingress and egress counter arrays via P4Runtime, and feeds those
 * values into ONOS' standard device statistics service.</p>
 */
@Component(
        immediate = true,
        enabled = true
)
public class Bmv2PortStatisticsProvider extends AbstractProvider implements DeviceProvider {

    private static final Logger log =
            LoggerFactory.getLogger(Bmv2PortStatisticsProvider.class);

    private static final String PROVIDER_SCHEME = "device";
    private static final String PROVIDER_ID = "org.onosproject.general.provider.device";
    private static final int POLL_INTERVAL_SECONDS = 2;
    private static final String P4_DEVICE_ID_PARAM = "device_id";

    private static final PiCounterId PORT_INGRESS_COUNTER =
            PiCounterId.of("IngressPipeImpl.port_ingress_counter");
    private static final PiCounterId PORT_EGRESS_COUNTER =
            PiCounterId.of("EgressPipeImpl.port_egress_counter");

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private DeviceProviderRegistry deviceProviderRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private NetworkConfigService networkConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private P4RuntimeController p4RuntimeController;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private PiPipeconfService pipeconfService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MainComponent mainComponent;

    private DeviceProviderService providerService;
    private volatile boolean active;

    public Bmv2PortStatisticsProvider() {
        super(new ProviderId(PROVIDER_SCHEME, PROVIDER_ID, true));
    }

    @Activate
    protected void activate() {
        active = true;
        registerProvider();
        schedulePoll(INITIAL_SETUP_DELAY);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        active = false;
        if (providerService != null) {
            deviceProviderRegistry.unregister(this);
        }
        providerService = null;
        log.info("Stopped");
    }

    @Override
    public void triggerProbe(DeviceId deviceId) {
        if (shouldPoll(deviceId)) {
            updateConfiguredPorts(deviceId);
            pollDevice(deviceId);
        }
    }

    @Override
    public void roleChanged(DeviceId deviceId, org.onosproject.net.MastershipRole newRole) {
        // This ancillary provider does not manage device roles.
    }

    @Override
    public boolean isReachable(DeviceId deviceId) {
        return deviceService.isAvailable(deviceId);
    }

    @Override
    public void changePortState(DeviceId deviceId, PortNumber portNumber, boolean enable) {
        // This ancillary provider only publishes statistics.
    }

    private void schedulePoll(int delaySeconds) {
        SharedScheduledExecutors.newTimeout(
                () -> mainComponent.getExecutorService().execute(this::pollAllDevices),
                delaySeconds, TimeUnit.SECONDS);
    }

    private void pollAllDevices() {
        try {
            if (!active) {
                return;
            }
            registerProvider();
            if (providerService == null) {
                return;
            }
            deviceService.getAvailableDevices().forEach(device -> {
                if (shouldPoll(device.id())) {
                    updateConfiguredPorts(device.id());
                    pollDevice(device.id());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Unable to update BMv2 port statistics", e);
        } finally {
            if (active) {
                schedulePoll(POLL_INTERVAL_SECONDS);
            }
        }
    }

    private void registerProvider() {
        if (providerService != null) {
            return;
        }
        try {
            providerService = deviceProviderRegistry.register(this);
        } catch (IllegalStateException e) {
            log.debug("Waiting for primary BMv2 device provider before publishing stats", e);
        }
    }

    private void pollDevice(DeviceId deviceId) {
        if (!shouldPoll(deviceId)) {
            return;
        }

        final Set<PortNumber> knownPorts = configuredPorts(deviceId);
        if (knownPorts.isEmpty()) {
            return;
        }

        readPortCounters(deviceId, knownPorts).ifPresent(statsByPort -> {
            final Collection<PortStatistics> portStatistics = statsByPort.entrySet().stream()
                    .map(entry -> buildPortStatistics(deviceId, entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());
            providerService.updatePortStatistics(deviceId, portStatistics);
        });
    }

    private boolean shouldPoll(DeviceId deviceId) {
        return active && providerService != null &&
                deviceService.isAvailable(deviceId) &&
                mastershipService.isLocalMaster(deviceId);
    }

    private void updateConfiguredPorts(DeviceId deviceId) {
        final Set<PortNumber> ports = configuredPorts(deviceId);
        if (ports.isEmpty()) {
            return;
        }
        if (providerService == null) {
            return;
        }
        final List<PortDescription> portDescriptions = ports.stream()
                .map(this::buildPortDescription)
                .collect(Collectors.toList());
        providerService.updatePorts(deviceId, portDescriptions);
    }

    private Set<PortNumber> configuredPorts(DeviceId deviceId) {
        final Set<PortNumber> ports = new HashSet<>();
        networkConfigService.getSubjects(LinkKey.class).forEach(linkKey -> {
            addPort(deviceId, linkKey.src(), ports);
            addPort(deviceId, linkKey.dst(), ports);
        });
        networkConfigService.getSubjects(ConnectPoint.class)
                .forEach(connectPoint -> addPort(deviceId, connectPoint, ports));
        deviceService.getPorts(deviceId).stream()
                .filter(Port::isEnabled)
                .map(Port::number)
                .forEach(ports::add);
        ports.removeIf(portNumber -> portNumber.toLong() == CPU_PORT_ID);
        return ports;
    }

    private void addPort(DeviceId deviceId, ConnectPoint connectPoint, Set<PortNumber> ports) {
        if (deviceId.equals(connectPoint.deviceId())) {
            ports.add(connectPoint.port());
        }
    }

    private PortDescription buildPortDescription(PortNumber portNumber) {
        return DefaultPortDescription.builder()
                .withPortNumber(portNumber)
                .isEnabled(true)
                .type(Port.Type.PACKET)
                .portSpeed(10_000)
                .build();
    }

    private Optional<Map<PortNumber, MutablePortStats>> readPortCounters(
            DeviceId deviceId, Set<PortNumber> knownPorts) {

        final Optional<Long> p4DeviceId = p4DeviceId(deviceId);
        if (!p4DeviceId.isPresent()) {
            return Optional.empty();
        }

        final Optional<PiPipeconf> pipeconf = pipeconfService.getPipeconf(deviceId);
        if (!pipeconf.isPresent()) {
            log.debug("Skipping port statistics for {}: pipeconf is not bound", deviceId);
            return Optional.empty();
        }

        final P4RuntimeClient client = p4RuntimeController.get(deviceId);
        if (client == null) {
            log.debug("Skipping port statistics for {}: P4Runtime client is not available", deviceId);
            return Optional.empty();
        }

        final P4RuntimeReadClient.ReadResponse response;
        try {
            response = client.read(p4DeviceId.get(), pipeconf.get())
                    .counterCells(Arrays.asList(PORT_INGRESS_COUNTER, PORT_EGRESS_COUNTER))
                    .submitSync();
        } catch (RuntimeException e) {
            log.debug("Unable to read port counters for {}", deviceId, e);
            return Optional.empty();
        }

        if (!response.isSuccess()) {
            log.debug("Unable to read port counters for {}: {}", deviceId, response.explanation());
            return Optional.empty();
        }

        final Map<PortNumber, MutablePortStats> statsByPort = new HashMap<>();
        knownPorts.forEach(portNumber -> statsByPort.put(portNumber, new MutablePortStats()));

        response.all(PiCounterCell.class).forEach(counterCell -> {
            final PortNumber portNumber = PortNumber.portNumber(counterCell.cellId().index());
            final MutablePortStats stats = statsByPort.get(portNumber);
            if (stats == null) {
                return;
            }
            if (PORT_INGRESS_COUNTER.equals(counterCell.cellId().counterId())) {
                stats.packetsReceived = counterCell.data().packets();
                stats.bytesReceived = counterCell.data().bytes();
            } else if (PORT_EGRESS_COUNTER.equals(counterCell.cellId().counterId())) {
                stats.packetsSent = counterCell.data().packets();
                stats.bytesSent = counterCell.data().bytes();
            }
        });

        return Optional.of(statsByPort);
    }

    private Optional<Long> p4DeviceId(DeviceId deviceId) {
        final BasicDeviceConfig config =
                networkConfigService.getConfig(deviceId, BasicDeviceConfig.class);
        if (config == null || config.managementAddress() == null) {
            log.debug("Skipping port statistics for {}: management address is not configured",
                      deviceId);
            return Optional.empty();
        }

        try {
            final URI managementAddress = URI.create(config.managementAddress());
            final String query = managementAddress.getRawQuery();
            if (query == null) {
                log.debug("Skipping port statistics for {}: management address has no {} query",
                          deviceId, P4_DEVICE_ID_PARAM);
                return Optional.empty();
            }
            for (String parameter : query.split("&")) {
                final String[] keyValue = parameter.split("=", 2);
                if (keyValue.length == 2 && P4_DEVICE_ID_PARAM.equals(keyValue[0])) {
                    return Optional.of(Long.parseLong(keyValue[1]));
                }
            }
        } catch (IllegalArgumentException e) {
            log.debug("Skipping port statistics for {}: invalid management address {}",
                      deviceId, config.managementAddress(), e);
            return Optional.empty();
        }

        log.debug("Skipping port statistics for {}: management address has no {} query",
                  deviceId, P4_DEVICE_ID_PARAM);
        return Optional.empty();
    }

    private PortStatistics buildPortStatistics(DeviceId deviceId,
                                               PortNumber portNumber,
                                               MutablePortStats stats) {
        return DefaultPortStatistics.builder()
                .setDeviceId(deviceId)
                .setPort(portNumber)
                .setPacketsReceived(stats.packetsReceived)
                .setPacketsSent(stats.packetsSent)
                .setBytesReceived(stats.bytesReceived)
                .setBytesSent(stats.bytesSent)
                .build();
    }

    private static final class MutablePortStats {
        private long packetsReceived;
        private long packetsSent;
        private long bytesReceived;
        private long bytesSent;
    }
}
