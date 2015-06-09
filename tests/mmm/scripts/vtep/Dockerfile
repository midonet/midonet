FROM ubuntu:14.04
MAINTAINER Ernest Artiaga <ernest@midokura.com>

WORKDIR /root

# Make sure the package repository is up to date.
RUN apt-get update

# Install add-apt-repository and syslogd
RUN apt-get install -y software-properties-common

# Install a basic SSH server
RUN apt-get install -y openssh-server
RUN mkdir -p /var/run/sshd
RUN echo 'root:root' | chpasswd
RUN sed -i 's/PermitRootLogin without-password/PermitRootLogin yes/' /etc/ssh/sshd_config

# Setup mighost repository (for ovenvswitch-vtep)
# (with workaround for mighost supporting saucy and not trusty)
RUN add-apt-repository ppa:mighost/ppa
RUN echo "deb http://ppa.launchpad.net/mighost/ppa/ubuntu saucy main" > /etc/apt/sources.list.d/mighost-ppa-trusty.list
RUN apt-get update

# Install OpenVSwitch 2.0
RUN apt-get install -y openvswitch-common openvswitch-switch openvswitch-vtep

# Configure openvswitch to start vtep
RUN cp /etc/default/openvswitch-vtep /etc/default/openvswitch-vtep.orig
RUN grep -v -e '^[[:blank:]]*ENABLE_OVS_VTEP[[:blank:]]*=' /etc/default/openvswitch-vtep.orig > /etc/default/openvswitch-vtep
RUN echo 'ENABLE_OVS_VTEP="true"' >> /etc/default/openvswitch-vtep

# Hack the openvswitch start script to enable management port 6632
RUN grep -q -e '--remote=ptcp:6632' /etc/init.d/openvswitch-vtep || sed -i.orig -e 's/^\([[:blank:]]*\)\(--remote=db:hardware_vtep.*\)/\1\2\n\1--remote=ptcp:6632 \\/' /etc/init.d/openvswitch-vtep

# Stop openvswitch to make sure everything is stable
RUN /etc/init.d/openvswitch-vtep stop

# Add initialization scripts
ADD configure_vtep.sh /root/configure_vtep.sh
ADD run.sh /root/run.sh
RUN chmod a+rx /root/configure_vtep.sh /root/run.sh

# Expose Vtep management port
EXPOSE 6632

# Expose Vtep tunnel port
EXPOSE 4789

CMD ["/root/run.sh"]
