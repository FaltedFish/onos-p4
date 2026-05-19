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

import org.onlab.util.ImmutableByteSequence;
import org.onlab.util.SharedScheduledExecutors;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.LinkKey;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.flow.DefaultFlowEntry;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.device.DefaultPortDescription;
import org.onosproject.net.device.DefaultPortStatistics;
import org.onosproject.net.device.DeviceProvider;
import org.onosproject.net.device.DeviceProviderRegistry;
import org.onosproject.net.device.DeviceProviderService;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortDescription;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.PiInstruction;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.pi.model.PiActionId;
import org.onosproject.net.pi.model.PiActionParamId;
import org.onosproject.net.pi.model.PiTableId;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionParam;
import org.onosproject.net.pi.runtime.PiTableAction;
import org.onosproject.net.provider.AbstractProvider;
import org.onosproject.net.provider.ProviderId;
import org.onosproject.net.statistic.StatisticStore;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.onosproject.ngsdn.tutorial.AppConstants.CPU_PORT_ID;
import static org.onosproject.ngsdn.tutorial.AppConstants.INITIAL_SETUP_DELAY;

/**
 * Publishes BMv2 flow counters as ONOS port statistics.
 *
 * <p>The BMv2/P4Runtime driver used in this lab does not expose native port
 * statistics, therefore the ONOS topology UI has no link load data. This
 * provider derives egress port counters from the P4 l2_exact_table direct
 * counters, publishes the netcfg-defined BMv2 ports, and feeds statistics into
 * ONOS' device statistics service and flow statistics store.</p>
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

    private static final PiTableId L2_EXACT_TABLE =
            PiTableId.of("IngressPipeImpl.l2_exact_table");
    private static final PiActionId SET_EGRESS_PORT =
            PiActionId.of("IngressPipeImpl.set_egress_port");
    private static final PiActionParamId PORT_NUM =
            PiActionParamId.of("port_num");

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private DeviceProviderRegistry deviceProviderRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private NetworkConfigService networkConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private StatisticStore statisticStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MainComponent mainComponent;

    private DeviceProviderService providerService;
    private final Map<FlowKey, FlowRule> syntheticStatisticRules = new HashMap<>();
    private final Map<FlowKey, Long> publishedFlowLastSeen = new HashMap<>();
    private final Map<DeviceId, Map<PortNumber, MutablePortStats>> publishedPortStats = new HashMap<>();
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
        clearSyntheticFlowStatistics();
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
                } else {
                    clearPublishedPortStatistics(device.id());
                    removeStaleFlowStatistics(device.id(), new HashSet<>());
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
            removeStaleFlowStatistics(deviceId, new HashSet<>());
            return;
        }

        final Set<PortNumber> knownPorts = configuredPorts(deviceId);
        if (knownPorts.isEmpty()) {
            removeStaleFlowStatistics(deviceId, new HashSet<>());
            return;
        }

        final Map<PortNumber, MutablePortStats> statsByPort = new HashMap<>();
        knownPorts.forEach(portNumber -> statsByPort.put(portNumber, new MutablePortStats()));
        final Set<FlowKey> liveStatisticFlows = new HashSet<>();
        final List<FlowStatisticUpdate> flowStatisticUpdates = new ArrayList<>();

        flowRuleService.getFlowEntries(deviceId).forEach(flowEntry -> {
            egressPort(flowEntry).ifPresent(portNumber -> {
                if (!knownPorts.contains(portNumber)) {
                    return;
                }
                final MutablePortStats stats = statsByPort.get(portNumber);
                stats.packetsSent += flowEntry.packets();
                stats.bytesSent += flowEntry.bytes();
                stats.lastSeen = Math.max(stats.lastSeen, flowEntry.lastSeen());
                flowStatisticUpdates.add(new FlowStatisticUpdate(flowEntry, portNumber));
            });
        });
        try {
            syncFlowStatistics(flowStatisticUpdates, liveStatisticFlows);
            removeStaleFlowStatistics(deviceId, liveStatisticFlows);
        } catch (RuntimeException e) {
            log.warn("Unable to update BMv2 flow statistics for {}", deviceId, e);
        }

        copyPeerEgressToIngress(deviceId, statsByPort);

        final Collection<PortStatistics> portStatistics = statsByPort.entrySet().stream()
                .map(entry -> buildPortStatistics(deviceId, entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        if (shouldPublishPortStatistics(deviceId, statsByPort)) {
            providerService.updatePortStatistics(deviceId, portStatistics);
        }
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

    private Optional<PortNumber> egressPort(FlowEntry flowEntry) {
        if (flowEntry.state() != FlowEntry.FlowEntryState.ADDED ||
                flowEntry.appId() != mainComponent.getAppId().id() ||
                !L2_EXACT_TABLE.equals(flowEntry.table())) {
            return Optional.empty();
        }

        for (Instruction instruction : flowEntry.treatment().allInstructions()) {
            if (instruction.type() != Instruction.Type.PROTOCOL_INDEPENDENT) {
                continue;
            }
            final PiTableAction tableAction = ((PiInstruction) instruction).action();
            if (tableAction.type() != PiTableAction.Type.ACTION) {
                continue;
            }
            final PiAction action = (PiAction) tableAction;
            if (!SET_EGRESS_PORT.equals(action.id())) {
                continue;
            }
            return action.parameters().stream()
                    .filter(param -> PORT_NUM.equals(param.id()))
                    .findFirst()
                    .map(this::portNumber);
        }
        return Optional.empty();
    }

    private PortNumber portNumber(PiActionParam param) {
        return PortNumber.portNumber(unsignedValue(param.value()));
    }

    private long unsignedValue(ImmutableByteSequence value) {
        long result = 0;
        for (byte valueByte : value.asArray()) {
            result = (result << Byte.SIZE) | (valueByte & 0xff);
        }
        return result;
    }

    private void copyPeerEgressToIngress(DeviceId deviceId,
                                         Map<PortNumber, MutablePortStats> statsByPort) {
        networkConfigService.getSubjects(LinkKey.class).forEach(link -> {
            if (!deviceId.equals(link.src().deviceId())) {
                return;
            }
            final MutablePortStats localStats = statsByPort.get(link.src().port());
            if (localStats == null) {
                return;
            }
            final MutablePortStats peerStats = egressStats(link.dst().deviceId(), link.dst().port());
            localStats.packetsReceived = peerStats.packetsSent;
            localStats.bytesReceived = peerStats.bytesSent;
            localStats.lastSeen = Math.max(localStats.lastSeen, peerStats.lastSeen);
        });

        for (Link link : linkService.getDeviceEgressLinks(deviceId)) {
            final MutablePortStats localStats = statsByPort.get(link.src().port());
            if (localStats == null) {
                continue;
            }
            final MutablePortStats peerStats = egressStats(link.dst().deviceId(), link.dst().port());
            localStats.packetsReceived = peerStats.packetsSent;
            localStats.bytesReceived = peerStats.bytesSent;
            localStats.lastSeen = Math.max(localStats.lastSeen, peerStats.lastSeen);
        }
    }

    private MutablePortStats egressStats(DeviceId deviceId, PortNumber portNumber) {
        final MutablePortStats stats = new MutablePortStats();
        flowRuleService.getFlowEntries(deviceId).forEach(flowEntry -> {
            if (egressPort(flowEntry).filter(portNumber::equals).isPresent()) {
                stats.packetsSent += flowEntry.packets();
                stats.bytesSent += flowEntry.bytes();
                stats.lastSeen = Math.max(stats.lastSeen, flowEntry.lastSeen());
            }
        });
        return stats;
    }

    private boolean shouldPublishPortStatistics(DeviceId deviceId,
                                                Map<PortNumber, MutablePortStats> statsByPort) {
        synchronized (publishedPortStats) {
            final Map<PortNumber, MutablePortStats> previous = publishedPortStats.get(deviceId);
            if (statsByPort.equals(previous)) {
                return false;
            }
            publishedPortStats.put(deviceId, copyStatsByPort(statsByPort));
            return true;
        }
    }

    private Map<PortNumber, MutablePortStats> copyStatsByPort(
            Map<PortNumber, MutablePortStats> statsByPort) {
        final Map<PortNumber, MutablePortStats> copy = new HashMap<>();
        statsByPort.forEach((portNumber, stats) -> copy.put(portNumber, stats.copy()));
        return copy;
    }

    private void clearPublishedPortStatistics(DeviceId deviceId) {
        synchronized (publishedPortStats) {
            publishedPortStats.remove(deviceId);
        }
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

    private void syncFlowStatistics(List<FlowStatisticUpdate> updates,
                                    Set<FlowKey> liveStatisticFlows) {
        synchronized (syntheticStatisticRules) {
            for (FlowStatisticUpdate update : updates) {
                final FlowEntry flowEntry = update.flowEntry;
                final FlowKey key = new FlowKey(flowEntry.deviceId(), flowEntry.id().value());
                final FlowRule syntheticRule = syntheticStatisticRule(flowEntry, update.portNumber);
                final FlowRule previousRule = syntheticStatisticRules.get(key);
                if (previousRule == null) {
                    statisticStore.prepareForStatistics(syntheticRule);
                    publishedFlowLastSeen.remove(key);
                } else if (!previousRule.exactMatch(syntheticRule)) {
                    statisticStore.removeFromStatistics(previousRule);
                    statisticStore.prepareForStatistics(syntheticRule);
                    publishedFlowLastSeen.remove(key);
                }
                syntheticStatisticRules.put(key, syntheticRule);
                update.syntheticRule = syntheticRule;
                liveStatisticFlows.add(key);
            }
        }

        updates.forEach(update -> {
            final FlowEntry flowEntry = update.flowEntry;
            final FlowKey key = new FlowKey(flowEntry.deviceId(), flowEntry.id().value());
            final long lastSeen = flowEntry.lastSeen();
            synchronized (syntheticStatisticRules) {
                if (Objects.equals(publishedFlowLastSeen.get(key), lastSeen)) {
                    return;
                }
                publishedFlowLastSeen.put(key, lastSeen);
            }
            statisticStore.addOrUpdateStatistic(
                    new DefaultFlowEntry(
                            update.syntheticRule,
                            flowEntry.state(),
                            flowEntry.life(),
                            flowEntry.packets(),
                            flowEntry.bytes()));
        });
    }

    private FlowRule syntheticStatisticRule(FlowEntry flowEntry, PortNumber portNumber) {
        final FlowRule.Builder builder = DefaultFlowRule.builder()
                .forDevice(flowEntry.deviceId())
                .forTable(flowEntry.table())
                .withSelector(flowEntry.selector())
                .withTreatment(DefaultTrafficTreatment.builder()
                                       .setOutput(portNumber)
                                       .build())
                .fromApp(mainComponent.getAppId())
                .withPriority(flowEntry.priority());

        if (flowEntry.isPermanent()) {
            builder.makePermanent();
        } else {
            builder.makeTemporary(flowEntry.timeout())
                    .withHardTimeout(flowEntry.hardTimeout());
        }
        return builder.build();
    }

    private void removeStaleFlowStatistics(DeviceId deviceId, Set<FlowKey> liveStatisticFlows) {
        synchronized (syntheticStatisticRules) {
            final Set<FlowKey> staleKeys = syntheticStatisticRules.keySet().stream()
                    .filter(key -> key.deviceId.equals(deviceId))
                    .filter(key -> !liveStatisticFlows.contains(key))
                    .collect(Collectors.toSet());
            staleKeys.forEach(key -> {
                statisticStore.removeFromStatistics(syntheticStatisticRules.get(key));
                syntheticStatisticRules.remove(key);
                publishedFlowLastSeen.remove(key);
            });
        }
    }

    private void clearSyntheticFlowStatistics() {
        synchronized (syntheticStatisticRules) {
            syntheticStatisticRules.values().forEach(statisticStore::removeFromStatistics);
            syntheticStatisticRules.clear();
            publishedFlowLastSeen.clear();
        }
        synchronized (publishedPortStats) {
            publishedPortStats.clear();
        }
    }

    private static final class MutablePortStats {
        private long packetsReceived;
        private long packetsSent;
        private long bytesReceived;
        private long bytesSent;
        private long lastSeen;

        private MutablePortStats copy() {
            final MutablePortStats copy = new MutablePortStats();
            copy.packetsReceived = packetsReceived;
            copy.packetsSent = packetsSent;
            copy.bytesReceived = bytesReceived;
            copy.bytesSent = bytesSent;
            copy.lastSeen = lastSeen;
            return copy;
        }

        @Override
        public int hashCode() {
            return Objects.hash(packetsReceived, packetsSent, bytesReceived, bytesSent, lastSeen);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MutablePortStats)) {
                return false;
            }
            final MutablePortStats other = (MutablePortStats) obj;
            return packetsReceived == other.packetsReceived &&
                    packetsSent == other.packetsSent &&
                    bytesReceived == other.bytesReceived &&
                    bytesSent == other.bytesSent &&
                    lastSeen == other.lastSeen;
        }
    }

    private static final class FlowStatisticUpdate {
        private final FlowEntry flowEntry;
        private final PortNumber portNumber;
        private FlowRule syntheticRule;

        private FlowStatisticUpdate(FlowEntry flowEntry, PortNumber portNumber) {
            this.flowEntry = flowEntry;
            this.portNumber = portNumber;
        }
    }

    private static final class FlowKey {
        private final DeviceId deviceId;
        private final long flowId;

        private FlowKey(DeviceId deviceId, long flowId) {
            this.deviceId = deviceId;
            this.flowId = flowId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(deviceId, flowId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FlowKey)) {
                return false;
            }
            final FlowKey other = (FlowKey) obj;
            return flowId == other.flowId &&
                    Objects.equals(deviceId, other.deviceId);
        }
    }
}
