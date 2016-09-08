FROM sandbox/midolman:base
MAINTAINER MidoNet (http://midonet.org)

RUN apt-get install -qy vpp
RUN echo manual | tee /etc/init/vpp.override
