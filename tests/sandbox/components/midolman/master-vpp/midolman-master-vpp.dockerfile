FROM sandbox/midolman:base
MAINTAINER MidoNet (http://midonet.org)

RUN touch /etc/init.d/vpp
RUN echo manual | tee /etc/init/vpp.override
RUN apt-get install -qy vpp
