package com.midokura.midonet.smoketest.mocks;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;

public class MockMidolmanManagement  extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(
            MockMidolmanManagement.class);

    private static class ServletListener implements ServletContextListener {

        @Override
        public void contextDestroyed(ServletContextEvent ctx) {
            // Do nothing
        }

        @Override
        public void contextInitialized(ServletContextEvent ctx) {
            //AppConfig.init(ctx.getServletContext());
        }
    }

    public MockMidolmanManagement() {
        super(new WebAppDescriptor.Builder(new String[] {
                "com.midokura.midolman.mgmt.rest_api.v1.resources",
                "com.midokura.midolman.mgmt.data" })
                .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
                .initParam(
                        "com.sun.jersey.spi.container.ContainerRequestFilters",
                        "com.midokura.midolman.mgmt.auth.NoAuthFilter")
                .contextParam("datastore_service",
                        "com.midokura.midolman.mgmt.data.MockDaoFactory")
                .contextParam("zk_conn_string", "")
                .contextParam("zk_timeout", "0")
                .contextParam("zk_root", "/test/midolman")
                .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
                .contextListenerClass(ServletListener.class)
                .contextPath("/test").build());
    }
}
