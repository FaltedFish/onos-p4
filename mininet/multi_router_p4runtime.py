#!/usr/bin/env python3
# Copyright 2013-present Barefoot Networks, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
Multi-router network with P4Runtime/ONOS support.
Creates a ring topology with 10+ routers, each with a host attached.
Switches connect to ONOS controller via P4Runtime gRPC.
(Modified for Native IPv6 and SRv6 support)
"""

from mininet.net import Mininet
from mininet.topo import Topo
from mininet.node import Switch
from mininet.log import setLogLevel, info, error, debug
from mininet.moduledeps import pathCheck
from mininet.cli import CLI
from p4_mininet import P4Host

import argparse
from time import sleep
import os
from pathlib import Path
import sys
import tempfile
import socket

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SW_PATH = "/usr/bin/simple_switch_grpc"
DEFAULT_JSON_PATH = str(PROJECT_ROOT / "p4build/ngsdn_tutorial/ngsdn_tutorial.json")
DEFAULT_P4INFO_PATH = str(PROJECT_ROOT / "p4build/ngsdn_tutorial.p4info.pb.txt")

parser = argparse.ArgumentParser(description='Multi-router P4Runtime/ONOS network (IPv6)')
parser.add_argument('--grpc-exe', help='Path to simple_switch_grpc executable',
                    type=str, action="store", default=DEFAULT_SW_PATH)
parser.add_argument('--json', help='Path to JSON config file',
                    type=str, action="store", default=DEFAULT_JSON_PATH)
parser.add_argument('--p4info', help='Path to P4Info protobuf file',
                    type=str, action="store", default=DEFAULT_P4INFO_PATH)
parser.add_argument('--thrift-port-base', help='Base Thrift server port',
                    type=int, action="store", default=9090)
parser.add_argument('--grpc-port-base', help='Base gRPC server port',
                    type=int, action="store", default=9559)
parser.add_argument('--num-routers', help='Number of routers',
                    type=int, action="store", default=10)
parser.add_argument('--num-hosts-per-router', help='Number of hosts per router',
                    type=int, action="store", default=1)
parser.add_argument('--topology', choices=['linear', 'ring', 'mesh'],
                    type=str, default='ring')
parser.add_argument('--onos-ip', help='ONOS controller IP address',
                    type=str, action="store", default="127.0.0.1")
parser.add_argument('--cpu-port', help='CPU port number',
                    type=int, action="store", default=255)
parser.add_argument('--device-id-base', help='Base device ID',
                    type=int, action="store", default=0)
parser.add_argument('--pcap-dump', help='Dump packets to pcap files',
                    action='store_true', default=False)

args = parser.parse_args()


class P4SwitchGrpc(Switch):
    """P4 virtual switch with P4Runtime/gRPC support."""
    device_id = 0

    def __init__(self, name, sw_path=None, json_path=None, p4info_path=None,
                 grpc_port=None, thrift_port=None, cpu_port=None,
                 device_id=None, pcap_dump=False,
                 log_console=False, verbose=False,
                 onos_ip="127.0.0.1", **kwargs):
        Switch.__init__(self, name, **kwargs)

        assert(sw_path)
        assert(json_path)
        pathCheck(sw_path)

        if not os.path.isfile(json_path):
            error("Invalid JSON file.\n")
            exit(1)

        self.sw_path = sw_path
        self.json_path = json_path
        self.p4info_path = p4info_path
        self.grpc_port = grpc_port
        self.thrift_port = thrift_port
        self.cpu_port = cpu_port
        self.verbose = verbose
        self.pcap_dump = pcap_dump
        self.log_console = log_console
        self.onos_ip = onos_ip
        logfile = "/tmp/p4s.{}.log".format(self.name)
        self.output = open(logfile, 'w')

        if device_id is not None:
            self.device_id = device_id
            P4SwitchGrpc.device_id = max(P4SwitchGrpc.device_id, device_id)
        else:
            self.device_id = P4SwitchGrpc.device_id
            P4SwitchGrpc.device_id += 1

    @classmethod
    def setup(cls):
        pass

    def check_switch_started(self, pid):
        import time
        timeout = 30
        start_time = time.time()
        while True:
            if not os.path.exists(os.path.join("/proc", str(pid))):
                return False
            if time.time() - start_time > timeout:
                return False
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                sock.settimeout(0.5)
                result = sock.connect_ex(("localhost", self.grpc_port))
            finally:
                sock.close()
            if result == 0:
                return True
            sleep(0.1)

    def start(self, controllers):
        info("Starting P4 switch {} with gRPC.\n".format(self.name))
        args = [self.sw_path]
        for port, intf in self.intfs.items():
            if not intf.IP():
                args.extend(['-i', str(port) + "@" + intf.name])

        if self.pcap_dump: args.append("--pcap")
        if self.thrift_port: args.extend(['--thrift-port', str(self.thrift_port)])
        args.extend(['--device-id', str(self.device_id)])
        if self.json_path: args.append(self.json_path)
        args.append("--")
        if self.grpc_port: args.extend(['--grpc-server-addr', "0.0.0.0:{}".format(self.grpc_port)])
        if self.cpu_port is not None: args.extend(['--cpu-port', str(self.cpu_port)])
        if self.log_console: args.append("--log-console")

        logfile = "/tmp/p4s.{}.log".format(self.name)
        info(' '.join(args) + "\n")

        pid = None
        with tempfile.NamedTemporaryFile() as f:
            self.cmd(' '.join(args) + ' >' + logfile + ' 2>&1 & echo $! >> ' + f.name)
            pid = int(f.read())
        debug("P4 switch {} PID is {}.\n".format(self.name, pid))

        sleep(0.5)

        if not self.check_switch_started(pid):
            error("P4 switch {} did not start correctly.\n".format(self.name))
            error("Check log at: {}\n".format(logfile))
            exit(1)
        info("P4 switch {} has been started.\n".format(self.name))

    def stop(self):
        self.output.flush()
        self.cmd('kill %' + self.sw_path)
        self.cmd('wait')
        self.deleteIntfs()

    def attach(self, intf):
        assert(0)

    def detach(self, intf):
        assert(0)


class MultiRouterP4Topo(Topo):
    def __init__(self, sw_path, json_path, p4info_path,
                 grpc_base, thrift_base, cpu_port, device_id_base,
                 onos_ip, num_routers, hosts_per_router,
                 pcap_dump, topology, **opts):
        Topo.__init__(self, **opts)

        for i in range(num_routers):
            self.addSwitch(
                'r{}'.format(i + 1), sw_path=sw_path, json_path=json_path, p4info_path=p4info_path,
                grpc_port=grpc_base + i, thrift_port=thrift_base + i, cpu_port=cpu_port,
                device_id=device_id_base + i, onos_ip=onos_ip, pcap_dump=pcap_dump
            )

        if topology == 'ring':
            for i in range(num_routers):
                self.addLink('r{}'.format(i + 1), 'r{}'.format((i + 1) % num_routers + 1))
        elif topology == 'linear':
            for i in range(num_routers - 1):
                self.addLink('r{}'.format(i + 1), 'r{}'.format(i + 2))
        elif topology == 'mesh':
            for i in range(num_routers):
                for j in range(1, 3):
                    if i + j < num_routers:
                        self.addLink('r{}'.format(i + 1), 'r{}'.format(i + j + 1))

        host_idx = 0
        for i in range(num_routers):
            for h in range(hosts_per_router):
                host_name = 'h{}'.format(host_idx + 1)
                # 
                self.addHost(
                    host_name,
                    ip=None,
                    mac='00:04:00:00:{:02x}:{:02x}'.format(i, h)
                )
                self.addLink(host_name, 'r{}'.format(i + 1))
                host_idx += 1


def main():
    if not os.path.exists(args.grpc_exe):
        error("simple_switch_grpc not found at: {}\n".format(args.grpc_exe))
        sys.exit(1)
    if not os.path.exists(args.json):
        error("JSON config not found at: {}\n".format(args.json))
        sys.exit(1)
    if not os.path.exists(args.p4info):
        error("P4Info not found at: {}\n".format(args.p4info))
        sys.exit(1)

    num_routers = args.num_routers
    hosts_per_router = args.num_hosts_per_router

    info("Creating {}-router {} topology\n".format(num_routers, args.topology))
    
    topo = MultiRouterP4Topo(
        sw_path=args.grpc_exe, json_path=args.json, p4info_path=args.p4info,
        grpc_base=args.grpc_port_base, thrift_base=args.thrift_port_base, cpu_port=args.cpu_port,
        device_id_base=args.device_id_base, onos_ip=args.onos_ip, num_routers=num_routers,
        hosts_per_router=hosts_per_router, pcap_dump=args.pcap_dump, topology=args.topology
    )

    net = Mininet(topo=topo, host=P4Host, switch=P4SwitchGrpc, controller=None)

    info("Starting network...\n")
    net.start()

    #
    info("Configuring hosts with Native IPv6...\n")
    host_idx = 0
    for i in range(num_routers):
        router_id = i + 1
        for h in range(hosts_per_router):
            host_id = h + 1
            host = net.get('h{}'.format(host_idx + 1))
            intf_name = host.defaultIntf().name

            # 
            host.cmd("sysctl -w net.ipv6.conf.all.disable_ipv6=0")
            host.cmd("sysctl -w net.ipv6.conf.default.disable_ipv6=0")
            host.cmd("sysctl -w net.ipv6.conf.lo.disable_ipv6=0")
            host.cmd("sysctl -w net.ipv6.conf.{}.disable_ipv6=0".format(intf_name))

            #
            host.cmd("ip addr flush dev {}".format(intf_name))

            # 
            ipv6_addr = "2001:{}:{}::10/64".format(router_id, host_id)
            ipv6_gw = "2001:{}:{}::254".format(router_id, host_id)

            host.cmd("ip -6 addr add {} dev {}".format(ipv6_addr, intf_name))
            host.cmd("ip -6 route add default via {}".format(ipv6_gw))
            
            host_idx += 1

    info("\n=== Topology ===\n")
    for i in range(num_routers):
        info("Router r{}: gRPC=localhost:{}, Thrift=localhost:{}\n".format(
            i + 1, args.grpc_port_base + i, args.thrift_port_base + i))

    info("\n=== Ready ===\n")
    info("{} routers with {} host(s) each (IPv6 Enabled)\n".format(num_routers, hosts_per_router))
    info("Host IPv6 Pattern: 2001:RouterID:HostID::10/64\n")
    info("Gateway Pattern:   2001:RouterID:HostID::254\n")
    info("\nUse 'pingall6' or 'h1 ping6 2001:2:1::10' for specific tests\n")

    CLI(net)
    net.stop()


if __name__ == '__main__':
    setLogLevel('info')
    main()
