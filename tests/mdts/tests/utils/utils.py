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
from copy import deepcopy
from functools import wraps
import inspect
import logging
from mdts.lib import subprocess_compat
from mdts.lib.tenants import list_tenants
from mdts.services import service
import subprocess
import tempfile
import time

LOG = logging.getLogger(__name__)


def wait_on_futures(futures):
    """ Takes a list of futures and wait on their results. """
    return map(lambda f: f.result(), futures)


def get_midonet_api():
    """
    :rtype: midonetclient.api.MidonetApi
    """
    return service.get_container_by_hostname('cluster1').get_midonet_api()


def get_neutron_api():
    """
    :rtype: neutronclient.v2_0.client.Client
    """
    return service.get_container_by_hostname('neutron').get_neutron_api()


def get_keystone_api():
    """
    :rtype: keystoneclient.
    """
    return service.get_container_by_hostname('keystone').get_keystone_api()


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
                            test_wrapped.__name__ = "%s (with failure %s)" % (
                                f.__name__, failure.__name__
                            )
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


def update_with_setup(func, setup, teardown):
    """
    In case a test function defined setup and teardown methods with the
    with_setup decorator, we need to keep those functions and apply them
    after the new setup and teardown functions.
    Nose details (look if it can be implemented in a better way):
      - Avoid executing setup two times when using func generators
        First for the inner test, and another one for the outer one
      - Look if it'd possible to use the with_setup method with a wrapped method

    :param func:
    :param setup:
    :param teardown:
    :return:
    """
    if setup:
        if hasattr(func, 'setup'):
            _old_s = func.setup

            def _s():
                try:
                    if not func.setup_executed:
                        func.setup_executed = True
                        func.teardown_executed = False
                        setup()
                        _old_s()
                except:
                    # Finish unbinding and cleaning topology
                    func.setup_executed = False
                    func.teardown_executed = True
                    teardown()
                    raise

            func.setup = _s
        else:
            def _s():
                try:
                    if not func.setup_executed:
                        func.setup_executed = True
                        func.teardown_executed = False
                        setup()
                except:
                    # Finish unbinding and cleaning topology
                    func.setup_executed = False
                    func.teardown_executed = True
                    teardown()
                    raise
            func.setup = _s

    if teardown:
        if hasattr(func, 'teardown'):
            _old_t = func.teardown

            def _t():
                if not func.teardown_executed:
                    func.setup_executed = False
                    func.teardown_executed = True
                    try:
                        _old_t()
                    except:
                        # Finish unbinding and cleaning topology
                        teardown()
                        raise
                    else:
                        teardown()

            func.teardown = _t
        else:
            def _t():
                if not func.teardown_executed:
                    func.setup_executed = False
                    func.teardown_executed = True
                    teardown()
            func.teardown = _t

    return func


# list -> (() -> ()) -> () -> generator (() -> ())
def bindings(*args, **kwargs):

    bindings_args = args
    # FRAME, FILE_NAME, LINE_NUM, FUNCTION_NAME, LINES, INDEX
    (frame, _, _, _, _, _) = inspect.getouterframes(inspect.currentframe())[1]
    test_mod = inspect.getmodule(frame)
    if 'binding_manager' in kwargs:
        BM = kwargs['binding_manager']
    else:
        BM = test_mod.BM

    def test_method_wrapper(f):
        f.setup_executed = False
        f.teardown_executed = True

        f_name = f.__name__
        # We first bind and then apply the with_setup method if it
        # was present. Teardown is done in reverse order.
        update_with_setup(f, BM.bind, BM.unbind)

        def test_wrapped():
            for binding in bindings_args:
                f.__name__ = "%s (with bindings %s)" % (
                    f_name,
                    binding.get('description'))
                test_wrapped.__name__ = f.__name__
                # We need to deepcopy here to prevent ourselves from
                # modifying it, as the binding can be shared among tests
                BM.set_binding_data(deepcopy(binding))
                yield f,

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
            execute_mn_conf(switch_flag, switch_id, config)
            return f
        return wrapped
    return decorated


def execute_mn_conf(switch_flag, switch_id, config):
    host = service.get_container_by_hostname("midolman1")
    conf_file = tempfile.NamedTemporaryFile()
    for k, v in config.items():
        conf_file.write("%s=%s\n" % (k, v))
    conf_file.flush()
    cmd = "mn-conf set %s %s < %s" % (switch_flag,
                                      switch_id,
                                      conf_file.name)
    host.exec_command(cmd, stream=False)
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


def await_port_active(vport_id, active=True, timeout=120, sleep_period=5):
    midonet_api = get_midonet_api()
    time.sleep(1)  # Initial, hopeful, short sleep
    while midonet_api.get_port(vport_id).get_active() != active:
        time.sleep(sleep_period)
        timeout -= sleep_period
        if timeout <= 0:
            raise Exception("Port {0} did not become {1}."
                            .format(vport_id,
                                    "active" if active else "inactive"))
