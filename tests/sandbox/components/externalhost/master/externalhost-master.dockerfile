FROM ubuntu-upstart:14.04
MAINTAINER MidoNet (https://midonet.org)

RUN apt-get -qy update
RUN apt-get -qy install curl git mz tcpdump nmap vlan --no-install-recommends

# Get pipework to allow arbitrary configurations on the container from the host
# Might get included into docker-networking in the future
RUN git clone http://github.com/jpetazzo/pipework /pipework
RUN mv /pipework/pipework /usr/bin/pipework

# Workaround to circumvent limitations with AppArmor profiles and docker
RUN mv /usr/sbin/tcpdump /usr/bin/tcpdump
RUN mv /sbin/dhclient /usr/bin/dhclient

ADD bin/run-external.sh /run-external.sh

CMD ["/run-external.sh"]
