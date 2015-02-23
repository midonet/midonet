FROM ubuntu:14.10
MAINTAINER Tomoe Sugihara "tomoe@midokura.com"

RUN apt-get update && apt-get clean
RUN apt-get install -q -y openvswitch-vtep
RUN apt-get install -q -y psmisc

# Configure vtep emulater
RUN sed -i 's/false/true/' /etc/default/openvswitch-vtep
RUN sed -ie 's/\(--remote=db:hardware_vtep,Global,managers\)/\1 --remote=ptcp:6632 -v/'  /etc/init.d/openvswitch-vtep

ENTRYPOINT (/etc/init.d/openvswitch-vtep start &) ; /bin/bash
