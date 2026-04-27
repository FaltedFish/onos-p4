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

import com.google.common.collect.Lists;
import org.onlab.packet.Ip6Address;
import org.onlab.packet.Ip6Prefix;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.util.ItemNotFoundException;
import org.onosproject.core.ApplicationId;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.HostLocation;
import org.onosproject.net.Link;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.PiCriterion;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupService;
import org.onosproject.net.host.HostEvent;
import org.onosproject.net.host.HostListener;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.link.LinkListener;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.pi.model.PiActionId;
import org.onosproject.net.pi.model.PiActionParamId;
import org.onosproject.net.pi.model.PiMatchFieldId;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionParam;
import org.onosproject.net.pi.runtime.PiActionProfileGroupId;
import org.onosproject.net.pi.runtime.PiTableAction;
import org.onosproject.ngsdn.tutorial.common.FabricDeviceConfig;
import org.onosproject.ngsdn.tutorial.common.Utils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.collect.Streams.stream;
import static org.onosproject.ngsdn.tutorial.AppConstants.INITIAL_SETUP_DELAY;

/**
 * IPv6 routing component for the behavioral-model multi-router topologies.
 *
 * <p>The original NG-SDN tutorial component assumes a leaf-spine fabric. This
 * version treats the ONOS link graph as a generic topology and installs one
 * /128 route per discovered IPv6 host on every available device. For remote
 * hosts, the next hop is the first switch on the shortest path computed from
 * ONOS links. For local hosts, the next hop is the host MAC and port.</p>
 */
@Component(
        immediate = true,
        enabled = true
)
public class Ipv6RoutingComponent {

    private static final Logger log = LoggerFactory.getLogger(Ipv6RoutingComponent.class);

    private static final long GROUP_INSERT_DELAY_MILLIS = 200;

    private final HostListener hostListener = new InternalHostListener();
    private final LinkListener linkListener = new InternalLinkListener();
    private final DeviceListener deviceListener = new InternalDeviceListener();

    private ApplicationId appId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private GroupService groupService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private NetworkConfigService networkConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private MainComponent mainComponent;

    @Activate
    protected void activate() {
        appId = mainComponent.getAppId();

        hostService.addListener(hostListener);
        linkService.addListener(linkListener);
        deviceService.addListener(deviceListener);

        mainComponent.scheduleTask(this::setUpAllDevices, INITIAL_SETUP_DELAY);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        hostService.removeListener(hostListener);
        linkService.removeListener(linkListener);
        deviceService.removeListener(deviceListener);

        log.info("Stopped");
    }

    private void setUpMyStationTable(DeviceId deviceId) {
        log.info("Adding My Station rules to {}...", deviceId);

        final MacAddress myStationMac = getMyStationMac(deviceId);
        final String tableId = "IngressPipeImpl.my_station_table";

        final PiCriterion match = PiCriterion.builder()
                .matchExact(PiMatchFieldId.of("hdr.ethernet.dst_addr"),
                        myStationMac.toBytes())
                .build();

        final PiTableAction action = PiAction.builder()
                .withId(PiActionId.of("NoAction"))
                .build();

        final FlowRule myStationRule = Utils.buildFlowRule(
                deviceId, appId, tableId, match, action);

        flowRuleService.applyFlowRules(myStationRule);
    }

    private GroupDescription createNextHopGroup(int groupId,
                                                Collection<MacAddress> nextHopMacs,
                                                DeviceId deviceId) {
        final String actionProfileId = "IngressPipeImpl.ecmp_selector";
        final String tableId = "IngressPipeImpl.routing_v6_table";
        final List<PiAction> actions = Lists.newArrayList();

        for (MacAddress nextHopMac : nextHopMacs) {
            actions.add(PiAction.builder()
                    .withId(PiActionId.of("IngressPipeImpl.set_next_hop"))
                    .withParameter(new PiActionParam(
                            PiActionParamId.of("dmac"),
                            nextHopMac.toBytes()))
                    .build());
        }

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

    class InternalHostListener implements HostListener {
        @Override
        public boolean isRelevant(HostEvent event) {
            switch (event.type()) {
                case HOST_ADDED:
                case HOST_UPDATED:
                case HOST_MOVED:
                    break;
                default:
                    return false;
            }
            final Host host = event.subject();
            return mastershipService.isLocalMaster(host.location().deviceId());
        }

        @Override
        public void event(HostEvent event) {
            mainComponent.getExecutorService().execute(() -> {
                log.info("{} event! host={}", event.type(), event.subject().id());
                setUpAllDevices();
            });
        }
    }

    class InternalLinkListener implements LinkListener {
        @Override
        public boolean isRelevant(LinkEvent event) {
            switch (event.type()) {
                case LINK_ADDED:
                case LINK_UPDATED:
                case LINK_REMOVED:
                    break;
                default:
                    return false;
            }
            final DeviceId src = event.subject().src().deviceId();
            final DeviceId dst = event.subject().dst().deviceId();
            return mastershipService.isLocalMaster(src) || mastershipService.isLocalMaster(dst);
        }

        @Override
        public void event(LinkEvent event) {
            mainComponent.getExecutorService().execute(() -> {
                log.info("{} event! linkSrc={}, linkDst={}",
                        event.type(),
                        event.subject().src().deviceId(),
                        event.subject().dst().deviceId());
                setUpAllDevices();
            });
        }
    }

    class InternalDeviceListener implements DeviceListener {
        @Override
        public boolean isRelevant(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_AVAILABILITY_CHANGED:
                case DEVICE_ADDED:
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
            mainComponent.getExecutorService().execute(() -> {
                log.info("{} event! deviceId={}", event.type(), event.subject().id());
                setUpAllDevices();
            });
        }
    }

    private void setUpL2NextHopRules(DeviceId deviceId) {
        for (Link link : linkService.getDeviceEgressLinks(deviceId)) {
            final DeviceId nextHopDevice = link.dst().deviceId();
            final PortNumber outPort = link.src().port();
            final MacAddress nextHopMac = getMyStationMac(nextHopDevice);
            flowRuleService.applyFlowRules(createL2NextHopRule(deviceId, nextHopMac, outPort));
        }
    }

    private void setUpRoutesFromDevice(DeviceId deviceId) {
        stream(hostService.getHosts()).forEach(host -> setUpRouteToHost(deviceId, host));
    }

    private void setUpRouteToHost(DeviceId deviceId, Host host) {
        final Collection<Ip6Address> hostIpv6Addrs = host.ipAddresses().stream()
                .filter(IpAddress::isIp6)
                .map(IpAddress::getIp6Address)
                .collect(Collectors.toSet());

        if (hostIpv6Addrs.isEmpty()) {
            log.debug("No IPv6 addresses for host {}, ignore", host.id());
            return;
        }

        final Optional<NextHop> nextHop = nextHopToHost(deviceId, host);
        if (!nextHop.isPresent()) {
            log.debug("No path from {} to host {}, skip", deviceId, host.id());
            return;
        }

        final MacAddress nextHopMac = nextHop.get().mac;
        final PortNumber outPort = nextHop.get().port;

        log.info("Adding routes on {} for host {} [{}] via {} port {}",
                deviceId, host.id(), hostIpv6Addrs, nextHopMac, outPort);

        flowRuleService.applyFlowRules(createL2NextHopRule(deviceId, nextHopMac, outPort));

        final int groupId = macToGroupId(nextHopMac);
        final GroupDescription group = createNextHopGroup(
                groupId, Collections.singleton(nextHopMac), deviceId);

        final List<FlowRule> flowRules = hostIpv6Addrs.stream()
                .map(IpAddress::toIpPrefix)
                .filter(IpPrefix::isIp6)
                .map(IpPrefix::getIp6Prefix)
                .map(prefix -> createRoutingRule(deviceId, prefix, groupId))
                .collect(Collectors.toList());

        insertInOrder(group, flowRules);
    }

    private Optional<NextHop> nextHopToHost(DeviceId srcDevice, Host host) {
        final HostLocation hostLocation = host.location();
        final DeviceId dstDevice = hostLocation.deviceId();

        if (srcDevice.equals(dstDevice)) {
            return Optional.of(new NextHop(host.mac(), hostLocation.port()));
        }

        final Optional<Link> firstHop = shortestPathFirstHop(srcDevice, dstDevice);
        if (!firstHop.isPresent()) {
            return Optional.empty();
        }

        final DeviceId nextHopDevice = firstHop.get().dst().deviceId();
        return Optional.of(new NextHop(
                getMyStationMac(nextHopDevice),
                firstHop.get().src().port()));
    }

    private Optional<Link> shortestPathFirstHop(DeviceId srcDevice, DeviceId dstDevice) {
        final Queue<DeviceId> queue = new ArrayDeque<>();
        final Set<DeviceId> visited = new HashSet<>();
        final Map<DeviceId, Link> parentLink = new HashMap<>();

        visited.add(srcDevice);
        queue.add(srcDevice);

        while (!queue.isEmpty()) {
            final DeviceId current = queue.remove();
            for (Link link : linkService.getDeviceEgressLinks(current)) {
                final DeviceId next = link.dst().deviceId();
                if (!visited.add(next)) {
                    continue;
                }
                parentLink.put(next, link);
                if (next.equals(dstDevice)) {
                    return unwindFirstHop(srcDevice, dstDevice, parentLink);
                }
                queue.add(next);
            }
        }

        return Optional.empty();
    }

    private Optional<Link> unwindFirstHop(DeviceId srcDevice, DeviceId dstDevice,
                                          Map<DeviceId, Link> parentLink) {
        DeviceId step = dstDevice;
        Link firstHop = parentLink.get(step);
        while (firstHop != null && !firstHop.src().deviceId().equals(srcDevice)) {
            step = firstHop.src().deviceId();
            firstHop = parentLink.get(step);
        }
        return Optional.ofNullable(firstHop);
    }

    private MacAddress getMyStationMac(DeviceId deviceId) {
        return getDeviceConfig(deviceId)
                .map(FabricDeviceConfig::myStationMac)
                .orElseThrow(() -> new ItemNotFoundException(
                        "Missing myStationMac config for " + deviceId));
    }

    private Optional<FabricDeviceConfig> getDeviceConfig(DeviceId deviceId) {
        return Optional.ofNullable(networkConfigService.getConfig(
                deviceId, FabricDeviceConfig.class));
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
            Thread.sleep(GROUP_INSERT_DELAY_MILLIS);
            flowRules.forEach(flowRuleService::applyFlowRules);
        } catch (InterruptedException e) {
            log.error("Interrupted!", e);
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void setUpAllDevices() {
        stream(deviceService.getAvailableDevices())
                .map(Device::id)
                .filter(mastershipService::isLocalMaster)
                .forEach(deviceId -> {
                    log.info("*** IPV6 ROUTING - Starting setup for {}...", deviceId);
                    setUpMyStationTable(deviceId);
                    setUpL2NextHopRules(deviceId);
                    setUpRoutesFromDevice(deviceId);
                });
    }

    private static final class NextHop {
        private final MacAddress mac;
        private final PortNumber port;

        private NextHop(MacAddress mac, PortNumber port) {
            this.mac = mac;
            this.port = port;
        }
    }
}
