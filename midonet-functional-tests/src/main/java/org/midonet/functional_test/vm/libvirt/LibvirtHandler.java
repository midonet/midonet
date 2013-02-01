/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test.vm.libvirt;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.functional_test.vm.HypervisorType;
import org.midonet.functional_test.vm.libvirt.builders.BgpOverlayDomainBuilder;
import org.midonet.functional_test.vm.libvirt.builders.OverlayDomainBuilder;
import static org.midonet.functional_test.vm.libvirt.LibvirtUtils.uriForHypervisorType;

/**
 * Handler to allow easy creation of typed custom virtual machine configuration
 * to be used in functional tests.
 */
public class LibvirtHandler {

    private final static Logger log =
        LoggerFactory.getLogger(LibvirtHandler.class);

    private String connectUri;
    private String templateName;
    private String baseImage;
    private HypervisorType hypervisorType;

    private Configuration configuration;

    private LibvirtHandler(HypervisorType hypervisorType, String uri) {
        this.hypervisorType = hypervisorType;
        this.connectUri = uri;

        configuration = new Configuration();

        this.baseImage = configuration.getBaseImageFileName();
    }

    public static LibvirtHandler forHypervisor(HypervisorType hypervisorType) {
        return new LibvirtHandler(hypervisorType,
                                  uriForHypervisorType(hypervisorType));
    }

    public void setTemplate(String template) {
        this.templateName = template;
    }

    public void setTemplateImage(String templateImage) {
        this.baseImage = templateImage;
    }

    public boolean isRuntimeConfigurationValid() {

        File workFolder = new File(configuration.getWorkFolderName());
        if (!workFolder.exists() || !workFolder.isDirectory()) {
            return false;
        }

        File imagesFolder = new File(configuration.getImagesFolderName());
        if (!imagesFolder.exists() || !imagesFolder.isDirectory()) {
            return false;
        }

        File templatesFolder = new File(configuration.getTemplatesFolderName());
        if (templatesFolder.exists() && !templatesFolder.isDirectory()) {
            return false;
        }

        File imageFile = new File(this.baseImage);
        //noinspection RedundantIfStatement
        if (!imageFile.exists() || !imageFile.isFile() || !imageFile.canRead()) {
            return false;
        }

        return true;
    }

    public OverlayDomainBuilder newDomain() {
        return
            new OverlayDomainBuilder(
                hypervisorType, connectUri, baseImage, templateName,
                configuration);
    }

    public BgpOverlayDomainBuilder newBgpDomain() {
        return
            new BgpOverlayDomainBuilder(
                hypervisorType, connectUri, baseImage, templateName,
                configuration);
    }

    public class Configuration {
        static final String RUNTIME_ENV_PROPS_KEY = "vm.runtime.environment";

        static final String DEFAULT_RUNTIME_ENV_PROPS =
            "/libvirt_runtime_env.properties";

        static final String VM_BASE_IMAGE = "libvirt.vm.base.image.file";
        static final String VM_IMAGES_FOLDER = "libvirt.vm.images.folder";
        static final String VM_WORK_FOLDER = "libvirt.vm.work.folder";
        static final String VM_TEMPLATES_FOLDER = "libvirt.templates.folder";

        Properties properties;

        public Configuration() {
            String configFile = System.getProperty(RUNTIME_ENV_PROPS_KEY,
                                                   DEFAULT_RUNTIME_ENV_PROPS);

            Properties properties = new Properties();
            try {
                properties.load(getClass().getResourceAsStream(configFile));
                log.info("Loaded properties from classpath " +
                             "resource: {}", configFile);

                this.properties = properties;
                setTemplateImage(properties.getProperty(VM_BASE_IMAGE));
            } catch (IOException e) {
                log.warn("Could not read properties from classpath resource: " +
                             configFile, e);
            }
        }

        public String getBaseImageFileName() {
            return properties.getProperty(VM_BASE_IMAGE);
        }

        public String getImagesFolderName() {
            return properties.getProperty(VM_IMAGES_FOLDER);
        }

        public String getWorkFolderName() {
            return properties.getProperty(VM_WORK_FOLDER);
        }

        public String getTemplatesFolderName() {
            return properties.getProperty(VM_TEMPLATES_FOLDER);
        }
    }
}
