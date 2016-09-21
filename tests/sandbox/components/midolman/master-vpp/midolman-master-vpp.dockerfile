FROM sandbox/midolman:base
MAINTAINER MidoNet (http://midonet.org)

# These two files need to be created so vpp is not started by upstart
RUN touch /etc/init.d/vpp
RUN echo manual | tee /etc/init/vpp.override
RUN apt-get update
RUN apt-get install -qy vpp
ADD conf/startup.conf /etc/vpp/startup.conf
