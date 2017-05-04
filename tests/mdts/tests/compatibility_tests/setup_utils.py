# Copyright 2017 Midokura SARL

from mdts import setup_package
from mdts.lib import sandbox
from mdts.services import service
from mdts.utils import conf

# Common compatibility setup utilities for installing new MN packages and
# resetting a sandbox

def install_packages(container_name, *packages):
    container = service.get_container_by_hostname(container_name)
    output, exec_id = container.exec_command(
        "sh -c 'env DEBIAN_FRONTEND=noninteractive apt-get install " + \
        "-qy --force-yes -o Dpkg::Options::=\"--force-confnew\" " + \
        "%s'" % (" ".join(packages)), stream=True)
    exit_code = container.check_exit_status(exec_id, output, timeout=60)
    if exit_code != 0:
        raise RuntimeError("Failed to update packages (%s)." % (packages))

def install(agents=["midolman1"]):
    # Install new package so the new version is updated immediately after reboot
    def install_agents():
        for agent in agents:
            install_packages(agent, "midolman/local", "midonet-tools/local")
        install_packages("cluster1", "midonet-cluster/local")
    return install_agents

def reset_sandbox():
    # Wipe out the sandbox and rebuild
    sandbox.kill_sandbox(conf.sandbox_name())
    sandbox.restart_sandbox('default_neutron+mitaka+compat',
                            conf.sandbox_name(),
                            'sandbox/override_compat',
                            'sandbox/provisioning/compat-provisioning.sh')
    # Reset cached containers and reload them (await for services to be up)
    service.loaded_containers = None
    setup_package()