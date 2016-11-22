#
# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
import logging
from mdts.services.interface import Interface
import time

LOG = logging.getLogger(__name__)


class VMGuest(Interface):

    def __init__(self, vm_id, **iface_kwargs):
        super(VMGuest, self).__init__(**iface_kwargs)
        self.vm_id = vm_id
        self.ifname = 'peth' + self.vm_id
        self.binding_ifname = 'veth' + self.vm_id
        # Setup hw_addr, ip_addr, ip_gw if defined in **iface_kwargs
        # using compute_host proxy

    # Public methods
    def get_vm_id(self):
        return self.vm_id

    def get_vm_ns(self):
        return 'vm' + self.vm_id

    def get_ifname(self):
        return self.ifname

    def get_binding_ifname(self):
        return self.binding_ifname

    def create(self):
        self.compute_host.create_vmguest(self)

    def destroy(self):
        self.compute_host.destroy_vmguest(self)

    # Inherited methods
    # FIXME: remove sync or look where it is used
    def do_execute(self, cmdline, timeout=None, should_succeed=True,
                   stream=False, on_netns=True):
        """
        Execute in the underlying host inside the net namespace
        :param cmdline:
        :param timeout:
        :return:
        """
        extra_timeout = 1
        pre_cmdline = '%s %s ' % (
            'ip netns exec %s' % self.get_vm_ns() if on_netns else '',
            'timeout %d' % (timeout + extra_timeout) if timeout else '')
        cmdline = pre_cmdline + cmdline
        result = self.compute_host.exec_command(cmdline,
                                                detach=False,
                                                stream=stream)
        # Extra timeout to account for docker exec warmup
        time.sleep(extra_timeout)
        return result
