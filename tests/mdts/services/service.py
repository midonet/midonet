#
# Copyright 2015 Midokura SARL
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#    http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
import importlib
import logging

from docker import Client
import re
import time
from mdts.services.interface import Interface

cli = Client(base_url='unix://var/run/docker.sock')
LOG = logging.getLogger(__name__)


class Service(object):

    def __init__(self, container_id):
        self.cli = cli
        self.container_id = container_id
        self.info = cli.inspect_container(container_id)
        timeout = 60
        wait_time = 1
        # Check first that the container is running
        while not self.is_container_running():
            if timeout == 0:
                raise RuntimeError("Container %s: timeout waiting to be running" % (
                    self.get_name()
                ))
            timeout -= wait_time
            time.sleep(wait_time)

    def _update_container_info(self):
        self.info = cli.inspect_container(self.container_id)

    # Helper methods to abstract from docker internals
    def get_type(self):
        return str(self.info['Config']['Labels']['type'])

    def get_name(self):
        return str(self.info['Name'].translate({ord('/'): None}))

    def get_container_id(self):
        return self.container_id

    def is_container_running(self):
        self._update_container_info()
        return self.info['State']['Running']

    def get_ip_address(self):
        return str(self.info['NetworkSettings']['IPAddress'])

    def get_mac_address(self):
        return str(self.info['NetworkSettings']['MacAddress'])

    def get_ports(self):
        return self.info['NetworkSettings']['Ports']

    def get_hostname(self):
        return str(self.info['Config']['Hostname'])

    def get_environment(self):
        return self.info['Config']['Env']

    def get_info(self):
        return self.info

    def get_service_name(self):
        raise NotImplementedError()

    def get_service_status(self):
        """
        Return the status of this service (FIXME: change it by constants

        :return: str specifying "up" or "down" (up by default so not all
        containers must implement this
        """
        return 'up'

    def get_service_logs(self):
        '''
        Returns an empty list by default
        :return: list of file names (full path) with the logs for that service
        '''
        return []

    def get_debug_logs(self):
        return None

    def set_log_marker(self, marker):
        logfiles = self.get_service_logs()
        for logfile in logfiles:
            self.exec_command("sh -c \"echo '%s' >> %s\"" % (marker, logfile), stream=False)

    def get_test_log(self, start_marker, end_marker):
        logfiles = self.get_service_logs()
        test_logs = {}
        for logfile in logfiles:
            test_log = "-----------------------\n"
            test_log += "%s - %s\n" % (self.get_service_name(), self.get_name())
            test_log += "%s\n" % logfile
            test_log += "-----------------------\n"
            test_log += self.exec_command(
                "sh -c \"sed -n '/%s/, /%s/ p' %s\"" % (
                    start_marker,
                    end_marker,
                    logfile),
                stream=False
            )
            test_logs[logfile] = test_log
        return test_logs

    def get_full_logs(self):
        logfiles = self.get_service_logs()
        test_logs = {}
        for logfile in logfiles:
            test_log = "-----------------------\n"
            test_log += "%s - %s\n" % (self.get_service_name(), self.get_name())
            test_log += "%s\n" % logfile
            test_log += "-----------------------\n"
            test_log += self.exec_command('cat %s' % logfile)
            test_logs[logfile] = test_log
        return test_logs

    def start(self, wait=False):
        return self.manage_service(operation='start', wait=wait)

    def stop(self, wait=False):
        return self.manage_service(operation='stop', wait=wait)

    def restart(self, wait_time=10, wait=False):
        # restart does not always sets status to stop so let's wait a bit
        time.sleep(wait_time)
        return self.manage_service(operation='restart', wait=wait)

    def manage_service(self, operation="start",
                       wait=False, timeout=120,
                       wait_time=5, raise_error=True):
        status = "up" if "start" in operation else "down"
        self.exec_command('service %s %s' %
                          (self.get_service_name(), operation))
        if wait:
            return self.wait_for_status(status=status,
                                        timeout=timeout,
                                        wait_time=wait_time,
                                        raise_error=raise_error)
        return True

    def create_provided(self, **iface_kwargs):
        iface_kwargs['compute_host'] = self
        return Interface(**iface_kwargs)

    def exec_command(self, cmd, stdout=True, stderr=False, tty=False,
                     detach=False, stream=False):
        """

        :param cmd:
        :param stdout:
        :param stderr:
        :param tty:
        :param detach:
        :param stream:
        :return: if detach: exec_id of the docker command for future inspect
                 if stream: a stream generator with the output
                 else: the result of the command
        """
        # Use raw representation to account for " and ' inside the cmd
        # cmd = "sh -c \"%s\"" % cmd

        LOG.debug('[%s] executing command: %s',
                  self.get_name(),
                  cmd)

        exec_id = cli.exec_create(self.get_name(),
                                  cmd,
                                  stdout=stdout,
                                  stderr=stderr,
                                  tty=tty)

        result = cli.exec_start(exec_id, detach=detach, stream=stream)
        if stream:
            # self._ensure_command_running(exec_id) # TODO
            # Result is a data blocking stream, exec_id for future checks
            return result, exec_id

        result = result.rstrip()
        # FIXME: different return result depending on params might be confusing
        # Awful pattern
        # Result is a string with the command output
        # return_code is the exit code
        return result

    def ensure_command_running(self, exec_id, timeout=20, raise_error=True):
        wait_time = 0.5
        while not cli.exec_inspect(exec_id)['Running']:
            if timeout == 0:
                LOG.debug('Command %s did not start' % exec_id)
                if raise_error:
                    raise Exception('Command %s did not start' % exec_id)
                else:
                    return False
            timeout -= wait_time
            time.sleep(wait_time)
        LOG.debug('Command started')
        return True

    def check_exit_status(self, exec_id, output_stream=None, timeout=20):
        wait_time = 1
        exec_info = cli.exec_inspect(exec_id)
        cmdline = exec_info['ProcessConfig']['entrypoint']
        for arg in exec_info['ProcessConfig']['arguments']:
            cmdline += " " + arg

        LOG.debug("Checking exit status of %s..." % cmdline)
        if output_stream:
            for output in output_stream:
                LOG.debug("Output: %s" % output)

        # Wait for command to finish after a certain amount of time
        while cli.exec_inspect(exec_id)['Running']:
            if timeout == 0:
                LOG.debug('Command %s timed out.' % cmdline)
                raise RuntimeError("Command %s timed out." % cmdline)
            timeout -= wait_time
            time.sleep(wait_time)
            LOG.debug('Command %s still running... [timeout in %d]' % (
                cmdline,
                timeout
            ))
        exec_info = cli.exec_inspect(exec_id)
        LOG.debug('Command %s %s' % (
            cmdline,
            'succeeded' if exec_info['ExitCode'] == 0 else 'failed'
        ))
        return exec_info['ExitCode']

    def wait_for_status(self, status, timeout=120, wait_time=5, raise_error=True):
        init_timeout = timeout
        while self.get_service_status() != status:
            if init_timeout == 0:
                if raise_error:
                    raise RuntimeError("Service %s: timeout waiting to be %s" % (
                        self.get_hostname(),
                        status))
                else:
                    LOG.debug("Service %s: timeout waiting to be %s" % (
                        self.get_hostname(),
                        status))
                    return False
            init_timeout -= wait_time
            time.sleep(wait_time)
        LOG.debug("Service %s: status is now %s" % (self.get_name(), status))
        return True

    # TODO: Make it generic so you can fail whatever component
    # (even packet failure in an interface)
    def inject_interface_failure(self, iface_name, wait_time=0):
        # put iptables rule or just set the interface down
        cmdline = "ip link set dev %s down" % iface_name
        result = self.exec_command(cmdline, stream=False)

    def eject_interface_failure(self, iface_name, wait_time=0):
        cmdline = "ip link set dev %s up" % iface_name
        result = self.exec_command(cmdline, stream=False)

    def inject_packet_loss(self, iface_name, wait_time=0):
        cmdline = "iptables -i %s -A INPUT -j DROP" % iface_name
        result = self.exec_command(cmdline, stream=False)
        cmdline = "iptables -i %s -A OUTPUT -j DROP" % iface_name
        result = self.exec_command(cmdline, stream=False)
        LOG.debug('[%s] Dropping packets coming from %s. %s' \
                  % (self.get_hostname(), iface_name, result))
        time.sleep(wait_time)

    def eject_packet_loss(self, iface_name, wait_time=0):
        cmdline = "iptables -i %s -D INPUT -j DROP" % iface_name
        result = self.exec_command(cmdline, stream=False)
        cmdline = "iptables -i %s -D OUTPUT -j DROP" % iface_name
        result = self.exec_command(cmdline, stream=False)
        LOG.debug('[%s] Receiving packets coming from %s. %s' \
                  % (self.get_hostname(), iface_name, result))
        time.sleep(wait_time)

def load_from_id(container_id):
    container_info = cli.inspect_container(container_id)
    fqn = container_info['Config']['Labels']['interface']
    module_name, class_name = tuple(fqn.rsplit('.', 1))
    _module = importlib.import_module(module_name)
    _class = getattr(_module, class_name)
    return _class(container_id)


loaded_containers = None

def get_container_by_hostname(container_hostname):
    global loaded_containers
    if not loaded_containers:
        loaded_containers = get_all_containers()
    for type, container_list in loaded_containers.items():
        for container in container_list:
            LOG.debug("GET_CONTAINER_BY_HOSTNAME type=% real=%s expected=%s" % (type, container.get_hostname(), container_hostname))
            if container.get_hostname() == container_hostname:
                return container
    raise RuntimeError('Container %s not found or loaded' % container_hostname)

# FIXME: this factory is not the best option
def get_all_containers(container_type=None):
    global loaded_containers

    # Load and cache all containers
    if not loaded_containers:
        running_containers = cli.containers()
        loaded_containers = {}
        for container in running_containers:
            if 'type' in container['Labels']:
                current_type = container['Labels']['type']
                container_instance = load_from_id(container['Id'])
                loaded_containers.setdefault(current_type, []).append(container_instance)
        for type, container_list in loaded_containers.items():
            sorted(container_list, key=lambda container: container.get_hostname())

    if container_type:
        if container_type in loaded_containers:
            return loaded_containers[container_type]
        else:
            return []
    return loaded_containers
