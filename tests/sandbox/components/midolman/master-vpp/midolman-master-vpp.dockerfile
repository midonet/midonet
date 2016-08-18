FROM sandbox/midolman:base
MAINTAINER MidoNet (http://midonet.org)

RUN apt-get install -qy git make
WORKDIR /
RUN git clone https://gerrit.fd.io/r/vpp
WORKDIR /vpp
ENV PLATFORM vpp_lite
RUN make UNATTENDED=yes install-dep bootstrap build
WORKDIR /