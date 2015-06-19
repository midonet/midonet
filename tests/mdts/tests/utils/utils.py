# Copyright 2014 Midokura SARL
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

"""
Utility functions for functional tests.
"""

from hamcrest import assert_that
from hamcrest import is_
from hamcrest import less_than

from midonetclient.api import MidonetApi

from mdts.lib.tenants import list_tenants
from mdts.tests.config import IP_ZOOKEEPER_HOSTS
from mdts.tests.config import MIDONET_API_URL

from functools import wraps
import inspect
import logging
import os
import subprocess
import time
import tempfile

from mdts.lib import subprocess_compat

FORMAT = '%(asctime)-15s %(module)s#%(funcName)s(%(lineno)d) %(message)s'
logging.basicConfig(format=FORMAT, level=logging.DEBUG)
LOG = logging.getLogger(__name__)

def wait_on_futures(futures):
    """ Takes a list of futures and wait on their results. """
    return map(lambda f: f.result(), futures)


def get_midonet_api():
    return MidonetApi(MIDONET_API_URL, 'admin','*')

#
# ``failures'' introduces another decorator @failures which can be used to
# inject various failures, combined with @bindings, in existing test cases.
# Note: @failures doesn't work alone and @failures (if any) has to be placed
# before @bindings, otherwise type mismatch error occurs.
#
# Example #1:
#
# @failures(NoFailure())
# @bindings(bindings1, bindings2)
# def test_foo():
#     [...]
#
# 'NoFailure' is a pseudo failure embracing the notion of "without failure."
# test_foo runs twice, with bindings1 and with bindings2, which is basically
# the same as the test without @failures,
#
# @bindings(bindings1, bindings2)
# def test_foo():
#     [...]
#
#
# Example #2:
#
# @failures(NoFailure(), NetifFailure('ns002', 'eth0', 30))
# @bindings(bindings1, bindings2)
# def test_foo():
#     [...]
#
# 'NetifFailure' emulates a failure in network interface. See
# qa/mdts/lib/failure/netif_failure.py for further details.
# In this setting, test_foo runs four times, enumerating all the combination
# of failures and bindings, say, with bindings1 (no failure), with bindings2
# (no failure), with bindings1 (under the failure) and with bindings2 (under
# the failure).
#
# Various failures are found in qa/mdts/lib/failure. Here is the list of
# available failures right now. See the source code for the full description
# and current status of each failure.
#
#     NoFailure        no failure
#     NetifFailure     set network interface down
#     PktFailure       filter incoming packets out using iptables
#     DDoSFailure      run DDoS attach using bonesi
#     ScanFailure      run host/port scan using nmap
#     CombinedFailure  make multiple failures happen simultaneously
#

# list -> (() -> generator (() -> ())) -> () -> generator (() -> ())
def failures(*args):
    failures = args

    def test_method_wrapper(f):
        def test_wrapped():
            for failure in failures:
                failure.inject()
                try:
                    if failure.is_resilient():
                        for g in f():
                            test_wrapped.__name__ = f.__name__ + \
                                ' (with_failure %s)' % failure.__name__
                            yield g
                finally:
                    failure.eject()
        # copied from nose.tools.make_decorator to preserve metadata
        test_wrapped.__dict__ = f.__dict__
        test_wrapped.__module__ = f.__module__
        if not hasattr(test_wrapped, 'compat_co_firstlineno'):
            test_wrapped.compat_co_firstlineno = f.func_code.co_firstlineno
        return test_wrapped
    return test_method_wrapper

# list -> (() -> ()) -> () -> generator (() -> ())
def bindings(*args):
    bindings = args
    # FRAME, FILE_NAME, LINE_NUM, FUNCTION_NAME, LINES, INDEX
    (frame, _, _, _, _, _) = inspect.getouterframes(inspect.currentframe())[1]
    test_mod = inspect.getmodule(frame)
    BM = test_mod.BM

    def test_method_wrapper(f):
        def test_wrapped():
            for b in bindings:
                BM.bind(data=b)
                time.sleep(1)
                test_wrapped.__name__ = f.__name__ + ' (with_bindings %s)' \
                                        % b.get('description')
                yield f,
                BM.unbind()
        # copied from nose.tools.make_decorator to preserve metadata
        test_wrapped.__dict__ = f.__dict__
        test_wrapped.__module__ = f.__module__
        if not hasattr(test_wrapped, 'compat_co_firstlineno'):
            test_wrapped.compat_co_firstlineno = f.func_code.co_firstlineno
        return test_wrapped
    return test_method_wrapper

def with_mn_conf(switch_flag, switch_id, config):
    """
    Decorator to execute_mn_conf with custom parameters before a test.
    For that, the @with_mn_conf decorator should be declared just on top
    of the test method.

    e.g.:
    @with_mn_conf("-t", "default",{
        "agent.loggers.debug":"DEBUG",
        "not.defined.key":"NULL"})
    def test_mn_conf():
        # Do something


    Keyword arguments:
    @param switch_flag: -t or -h (template or host)
    @param switch_id: template name or host id
    @param config: dictionary with key value pairs for config
    """
    def decorated(f):
        @wraps(f)
        def wrapped(*args, **kwargs):
            # Execute mn-conf command with config string
            print "Before mn-conf wrapped"
            execute_mn_conf(switch_flag, switch_id, config)
            print "After mn-conf wrapped"
            return f
        return wrapped
    return decorated

def execute_mn_conf(switch_flag, switch_id, config):
    conf_file = tempfile.NamedTemporaryFile()
    for k,v in config.items():
        conf_file.write("%s=%s\n" % (k, v))
    conf_file.flush()
    process = subprocess.Popen("mn-conf set %s %s < %s" % (switch_flag,
                                                           switch_id,
                                                           conf_file.name),
                               shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = process.communicate()
    conf_file.close()

def clear_virtual_topology_for_tenants(tenant_name_prefix):
    """
    Delete the virtual topology for tenants whose name starts
    with tenant_name_prefix

    Args:
        tenant_name_prefix: the prefix of the tenant name
    """

    api = get_midonet_api()
    tenants = filter(lambda x: x.name.startswith(tenant_name_prefix),
                     list_tenants())

    for tenant in tenants:
        # delete routers
        for router in api.get_routers({'tenant_id': tenant.id}):
            # unlink and delete all router ports
            for port in router.get_ports():
                if port.get_type() == 'InteriorRouter':
                    if port.get_peer_id():
                        port.unlink()
                port.delete()
            # delete router
            router.delete()

        # delete bridges
        for bridge in api.get_bridges({'tenant_id': tenant.id}):
            # unlink and delete all bridge ports
            for port in bridge.get_ports():
                if port.get_type() == 'InteriorBridge':
                    if port.get_peer_id():
                        port.unlink()
                port.delete()
            # delete router
            bridge.delete()

        # delete chains
        for chain in api.get_chains({'tenant_id': tenant.id}):
            for rule in chain.get_rules():
                rule.delete()
            chain.delete()


def clear_physical_topology():
    logging.debug('-' * 80)
    logging.debug("clear")
    logging.debug('-' * 80)

    logging.debug("clear physical topology")

    def _clear():
        cmdline = ("for ns in `ip netns | grep -- ns-if-`; "
                   "do sudo -i ip netns delete $ns 2>/dev/null; done")
        logging.debug("cmdline: " + cmdline)
        try:
            subprocess_compat.check_output(cmdline, shell=True)
        except subprocess.CalledProcessError:
            pass

        cmdline = ("for iface in `ip link | grep -- if- | cut -d ':' -f 2`; "
                   "do sudo -i ip link delete $iface 2>/dev/null; done")
        logging.debug("cmdline: " + cmdline)
        try:
            subprocess_compat.check_output(cmdline, shell=True)
        except subprocess.CalledProcessError:
            pass

    _clear()
    _clear()
    _clear()

    logging.debug('-' * 80)
    logging.debug("end clear")
    logging.debug('-' * 80)


def ipv4_int(ipv4_str):
    """Returns an integer corresponding to the given ipV4 address."""
    parts = ipv4_str.split('.')
    if len(parts) != 4:
        raise Exception('Incorrect IPv4 address format: %s' % ipv4_str)

    addr_int = 0
    for part in parts:
        part_int = 0
        try:
            part_int = int(part)
        except:
            raise Exception('Incorrect IPv4 address format: %s' % ipv4_str)
        addr_int = addr_int * 256 + part_int
    return addr_int

def get_top_dir():
    """Returns the root dir of MDTS"""
    topdir = os.path.realpath(os.path.dirname(__file__) + '../../../../')
    return topdir

def get_midolman_script_dir():
    """Returns abs path to Midolman scripts directory"""
    return get_top_dir() + '/mmm/scripts/midolman'


def start_midolman_agents():
    """Starts all Midolman agents."""
    subprocess_compat.check_output(
        'cd %s; ./start' % get_midolman_script_dir(), shell=True)


def stop_midolman_agents():
    """Stops all Midolman agents."""
    subprocess_compat.check_output(
        'cd %s; ./stop' % get_midolman_script_dir(), shell=True)


def check_all_midolman_hosts(alive):
    midonet_api = get_midonet_api()
    max_attempts = 20
    counter = 0
    sleep_time = 10
    failed_midolman = None
    for _ in range(max_attempts):
        try:
            for h in midonet_api.get_hosts():
                assert_that(h.is_alive(), is_(alive),
                            'A Midolman %s is alive.' % h.get_id())
            LOG.debug("Midolman agents took %d attempts of %d s to be up." % (counter,
                                                                              sleep_time))
            break
        except AssertionError:
            failed_midolman = h.get_id()
            time.sleep(sleep_time)
            counter += 1
    assert_that(counter, less_than(max_attempts),
                'Timeout checking for Midolman %s liveness' % failed_midolman)

def await_port_active(vport_id, active=True):
    timeout = 60
    midonet_api = get_midonet_api()
    while midonet_api.get_port(vport_id).get_active() != active:
        time.sleep(1)
        timeout -= 1
        if timeout == 0:
            raise Exception("Port did not become {0}."
                            .format("active" if active else "inactive"))

def check_all_zookeeper_hosts(alive=True):
    for zk_host in IP_ZOOKEEPER_HOSTS:
        check_zookeeper_host(zk_host, alive)

def check_zookeeper_host(zk_host, alive=True):
    timeout = 120
    sleep_period = 5
    while True:
        nc_cmd = "nc %s 2181" % zk_host
        p1 = subprocess.Popen("echo stat".split(), stdout=subprocess.PIPE)
        p2 = subprocess.Popen(nc_cmd.split(), stdin=p1.stdout, stdout=subprocess.PIPE)
        stdout, stderr = p2.communicate()
        LOG.debug("ZK_HOST %s stats. Should be alive(%s):\n%s\n" % (zk_host, alive, stdout))
        if ('follower' in stdout or 'leader' in stdout) == alive:
            return True
        else:
            time.sleep(sleep_period)
            timeout -= sleep_period
            if timeout <= 0:
                raise Exception("Zookeeper host {0} alive."
                                .format("IS NOT" if alive else "IS"))
