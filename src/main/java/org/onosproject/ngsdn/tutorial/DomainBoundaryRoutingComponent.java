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

import org.onlab.packet.Ip6Prefix;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.PiCriterion;
import org.onosproject.net.group.Group;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupKey;
import org.onosproject.net.group.GroupService;
import org.onosproject.net.pi.model.PiActionProfileId;
import org.onosproject.net.pi.model.PiActionId;
import org.onosproject.net.pi.model.PiActionParamId;
import org.onosproject.net.pi.model.PiMatchFieldId;
import org.onosproject.net.pi.model.PiTableId;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionParam;
import org.onosproject.net.pi.runtime.PiGroupKey;
import org.onosproject.net.pi.runtime.PiActionProfileGroupId;
import org.onosproject.net.pi.runtime.PiTableAction;
import org.onosproject.ngsdn.tutorial.common.DomainBoundaryConfig;
import org.onosproject.ngsdn.tutorial.common.Utils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.onosproject.ngsdn.tutorial.AppConstants.INITIAL_SETUP_DELAY;

/**
 * Installs static SID routes needed at cross-domain router boundaries.
 */
@Component(
        immediate = true,
        enabled = true
)
public class DomainBoundaryRoutingComponent {

    private static final Logger log =
            LoggerFactory.getLogger(DomainBoundaryRoutingComponent.class);

    private static final long GROUP_INSERT_DELAY_MILLIS = 200;
    private static final int GROUP_READY_RETRIES = 20;

    private final NetworkConfigListener configListener = new InternalConfigListener();
    private final DeviceListener deviceListener = new InternalDeviceListener();

    private ApplicationId appId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private GroupService groupService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private NetworkConfigService networkConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MainComponent mainComponent;

    @Activate
    protected void activate() {
        appId = mainComponent.getAppId();

        networkConfigService.addListener(configListener);
        deviceService.addListener(deviceListener);
        mainComponent.scheduleTask(this::setUpConfiguredRoutes, INITIAL_SETUP_DELAY);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        networkConfigService.removeListener(configListener);
        deviceService.removeListener(deviceListener);

        log.info("Stopped");
    }

    private synchronized void setUpConfiguredRoutes() {
        final Optional<DomainBoundaryConfig> config = getConfig();
        if (!config.isPresent()) {
            return;
        }

        final List<DomainBoundaryConfig.BoundaryRoute> routes = config.get().routes();
        if (routes == null || routes.isEmpty()) {
            return;
        }

        routes.stream()
                .filter(route -> deviceService.isAvailable(route.deviceId()))
                .filter(route -> mastershipService.isLocalMaster(route.deviceId()))
                .forEach(this::setUpBoundaryRoute);
    }

    private void setUpBoundaryRoute(DomainBoundaryConfig.BoundaryRoute route) {
        final DeviceId deviceId = route.deviceId();
        final MacAddress nextHopMac = route.nextHopMac();
        final PortNumber outPort = route.port();

        log.info("Adding cross-domain neighbor L2 next hop on {} via {} port {}",
                deviceId, nextHopMac, outPort);
        flowRuleService.applyFlowRules(createL2NextHopRule(deviceId, nextHopMac, outPort));

        final int groupId = macToGroupId(nextHopMac);
        final GroupDescription group = createNextHopGroup(
                groupId, Collections.singleton(nextHopMac), deviceId);

        final Collection<FlowRule> flowRules = route.prefixes().stream()
                .map(prefix -> createRoutingRule(deviceId, prefix, groupId))
                .collect(java.util.stream.Collectors.toList());

        log.info("Adding {} cross-domain neighbor SID route(s) on {} via {}",
                flowRules.size(), deviceId, nextHopMac);
        insertInOrder(group, flowRules);
    }

    private GroupDescription createNextHopGroup(int groupId,
                                                Collection<MacAddress> nextHopMacs,
                                                DeviceId deviceId) {
        final String actionProfileId = "IngressPipeImpl.ecmp_selector";
        final String tableId = "IngressPipeImpl.routing_v6_table";
        final List<PiAction> actions = nextHopMacs.stream()
                .map(nextHopMac -> PiAction.builder()
                        .withId(PiActionId.of("IngressPipeImpl.set_next_hop"))
                        .withParameter(new PiActionParam(
                                PiActionParamId.of("dmac"),
                                nextHopMac.toBytes()))
                        .build())
                .collect(java.util.stream.Collectors.toList());

        return Utils.buildSelectGroup(
                deviceId, tableId, actionProfileId, groupId, actions, appId);
    }

    private FlowRule createRoutingRule(DeviceId deviceId, Ip6Prefix ip6Prefix,
                                       int groupId) {
        final String tableId = "IngressPipeImpl.routing_v6_table";
        final PiCriterion match = PiCriterion.builder()
                .matchLpm(PiMatchFieldId.of("hdr.ipv6.dst_addr"),
                        ip6Prefix.address().toOctets(),
                        ip6Prefix.prefixLength())
                .build();

        final PiTableAction action = PiActionProfileGroupId.of(groupId);

        return Utils.buildFlowRule(
                deviceId, appId, tableId, match, action);
    }

    private FlowRule createL2NextHopRule(DeviceId deviceId, MacAddress nextHopMac,
                                         PortNumber outPort) {
        final String tableId = "IngressPipeImpl.l2_exact_table";
        final PiCriterion match = PiCriterion.builder()
                .matchExact(PiMatchFieldId.of("hdr.ethernet.dst_addr"),
                        nextHopMac.toBytes())
                .build();

        final PiAction action = PiAction.builder()
                .withId(PiActionId.of("IngressPipeImpl.set_egress_port"))
                .withParameter(new PiActionParam(
                        PiActionParamId.of("port_num"),
                        outPort.toLong()))
                .build();

        return Utils.buildFlowRule(
                deviceId, appId, tableId, match, action);
    }

    private Optional<DomainBoundaryConfig> getConfig() {
        return Optional.ofNullable(networkConfigService.getConfig(
                appId, DomainBoundaryConfig.class));
    }

    private int macToGroupId(MacAddress mac) {
        return mac.hashCode() & 0x7fffffff;
    }

    private void insertInOrder(GroupDescription group, Collection<FlowRule> flowRules) {
        if (flowRules.isEmpty()) {
            return;
        }
        try {
            groupService.addGroup(group);
            if (waitForGroupAdded(group)) {
                flowRules.forEach(flowRuleService::applyFlowRules);
            } else {
                log.warn("Group {} on {} was not ready; skip {} dependent flow(s)",
                        group.givenGroupId(), group.deviceId(), flowRules.size());
            }
        } catch (InterruptedException e) {
            log.error("Interrupted!", e);
            Thread.currentThread().interrupt();
        }
    }

    private boolean waitForGroupAdded(GroupDescription description)
            throws InterruptedException {
        final GroupKey groupKey = description.appCookie();
        for (int attempt = 0; attempt < GROUP_READY_RETRIES; attempt++) {
            final Group group = groupService.getGroup(description.deviceId(), groupKey);
            if (group != null && group.state() == Group.GroupState.ADDED) {
                return true;
            }
            Thread.sleep(GROUP_INSERT_DELAY_MILLIS);
        }
        return false;
    }

    private void scheduleSetup() {
        mainComponent.getExecutorService().execute(this::setUpConfiguredRoutes);
    }

    private class InternalConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            switch (event.type()) {
                case CONFIG_ADDED:
                case CONFIG_UPDATED:
                case CONFIG_REMOVED:
                    if (event.configClass().equals(DomainBoundaryConfig.class)) {
                        scheduleSetup();
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private class InternalDeviceListener implements DeviceListener {
        @Override
        public boolean isRelevant(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_ADDED:
                case DEVICE_AVAILABILITY_CHANGED:
                    break;
                default:
                    return false;
            }
            final DeviceId deviceId = event.subject().id();
            return mastershipService.isLocalMaster(deviceId) &&
                    deviceService.isAvailable(deviceId);
        }

        @Override
        public void event(DeviceEvent event) {
            scheduleSetup();
        }
    }
}
