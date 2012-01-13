/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.vm.libvirt;

import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.libvirt.builders.BgpOverlayDomainBuilder;
import com.midokura.midonet.smoketest.vm.libvirt.builders.OverlayDomainBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import static com.midokura.midonet.smoketest.vm.libvirt.LibvirtUtils.uriForHypervisorType;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/15/11
 * Time: 5:32 PM
 */
public class LibvirtHandler {

    private final static Logger log = LoggerFactory.getLogger(LibvirtHandler.class);


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
        return new LibvirtHandler(hypervisorType, uriForHypervisorType(hypervisorType));
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

        File baseImgFile = new File(baseImage);
        //noinspection RedundantIfStatement
        if (!baseImgFile.exists() || !baseImgFile.isFile() || !baseImgFile.canRead()) {
            return false;
        }

        return true;
    }

    public OverlayDomainBuilder newDomain() {
        return
            new OverlayDomainBuilder(
                hypervisorType, connectUri, baseImage, templateName, configuration);
    }

    public BgpOverlayDomainBuilder newBgpDomain() {
        return
            new BgpOverlayDomainBuilder(
                hypervisorType, connectUri, baseImage, templateName, configuration);
    }

    public class Configuration {
        private static final String RUNTIME_ENV_PROPERTIES = "/libvirt_runtime_env.properties";

        private static final String VM_BASE_IMAGE = "libvirt.vm.base.image.file";
        private static final String VM_IMAGES_FOLDER = "libvirt.vm.images.folder";
        private static final String VM_WORK_FOLDER = "libvirt.vm.work.folder";
        private static final String VM_TEMPLATES_FOLDER = "libvirt.templates.folder";

        Properties properties;

        public Configuration() {
            Properties properties = new Properties();
            try {
                properties.load(getClass().getResourceAsStream(RUNTIME_ENV_PROPERTIES));
                log.info("Loaded properties from classpath resource: {}", RUNTIME_ENV_PROPERTIES);
                this.properties = properties;

                setTemplateImage(properties.getProperty(VM_BASE_IMAGE));
            } catch (IOException e) {
                log.warn("Could not read properties from classpath resource: " + RUNTIME_ENV_PROPERTIES, e);
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
