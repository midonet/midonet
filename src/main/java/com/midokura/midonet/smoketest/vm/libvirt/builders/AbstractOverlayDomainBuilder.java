/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.vm.libvirt.builders;

import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import com.midokura.midonet.smoketest.vm.libvirt.DomainController;
import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import com.midokura.tools.process.DrainTargets;
import com.midokura.tools.process.ProcessHelper;
import com.midokura.tools.process.ProcessOutputDrainer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/30/11
 * Time: 12:15 PM
 */
public abstract class AbstractOverlayDomainBuilder<Builder extends AbstractOverlayDomainBuilder<Builder>>
    extends AbstractBuilder<Builder, VMController> {
    private final static Logger log = LoggerFactory.getLogger(AbstractOverlayDomainBuilder.class);

    private String domainName;
    private String hostName;
    private String networkDevice;

    private String baseImage;
    private String connectUri;
    private String templateName;

    private LibvirtHandler.Configuration configuration;
    private HypervisorType hypervisorType;

    private final String BASIC_RESOURCE_ROOT = "/com/midokura/midonet/smoketest/vm/libvirt";

    protected abstract Builder self();

    public AbstractOverlayDomainBuilder(HypervisorType hypervisorType, String connectUri,
                                        String baseImage, String templateName,
                                        LibvirtHandler.Configuration configuration) {
        this.baseImage = baseImage;
        this.connectUri = connectUri;
        this.hypervisorType = hypervisorType;
        this.templateName = templateName;
        this.configuration = configuration;
    }

    public VMController build() {
        deleteDomainIfExists();

        extractToolsFromResources();

        String overlayImage = buildOverlayImage();

        String domainXml = updateTemplate(domainName, overlayImage, networkDevice);

        if (domainXml == null || domainXml.trim().length() == 0) {
            return null;
        }

        try {
            Connect connect = new Connect(connectUri);
            Domain domain = connect.domainDefineXML(domainXml);
            return new DomainController(hypervisorType, domain, hostName);
        } catch (LibvirtException e) {
            log.error("Error while defining new domain: " + domainName, e);
        }

        return null;
    }

    private void extractToolsFromResources() {

        String tools[] = {
            "create_domain_overlay.sh", "create_bgp_overlay.sh", "functions.sh"
        };

        String targetWorkFolder = getConfiguration().getWorkFolderName();
        String TOOL_RESOURCES = BASIC_RESOURCE_ROOT + "/tools";

        try {

            for (String toolName : tools) {

                File targetFile = new File(targetWorkFolder, toolName);

                targetFile.delete();

                URL sourceResourceURL =
                    getClass().getResource(TOOL_RESOURCES + "/" + toolName);

                if (sourceResourceURL != null) {
                    FileUtils.copyURLToFile(sourceResourceURL, targetFile);
                    targetFile.setExecutable(true, false);
                }
            }
        } catch (IOException e) {
            log.error("Exception while extracting tools from resources: ", e);
        }
    }


    public Builder setDomainName(String name) {
        this.domainName = name;
        if (this.hostName == null) {
            this.hostName = domainName;
        }
        return self();
    }

    public Builder setHostName(String hostName) {
        this.hostName = hostName;
        return self();
    }

    public Builder setNetworkDevice(String networkDeviceName) {
        this.networkDevice = networkDeviceName;
        return self();
    }

    protected String getDomainName() {
        return domainName;
    }

    protected String getHostName() {
        return hostName;
    }

    protected String getTemplateName() {
        return templateName;
    }

    public String getBaseImage() {
        return baseImage;
    }

    private void deleteDomainIfExists() {
        try {
            Connect connect = new Connect(connectUri);

            // undefine this domain if it was already defined
            // (this works for domains that aren't running yet).
            String[] existingDomains = connect.listDefinedDomains();
            for (String existingDomain : existingDomains) {
                if (existingDomain.equals(domainName)) {
                    Domain domain = connect.domainLookupByName(domainName);
                    domain.undefine();
                    return;
                }
            }

            // our domain might be running so we look to see
            int[] runningDomainIds = connect.listDomains();
            for (int runningDomainId : runningDomainIds) {
                Domain runningDomain = connect.domainLookupByID(runningDomainId);
                if (runningDomain != null && runningDomain.getName().equals(domainName)) {
                    // this is our domain .. so we stop it first (forcibly)
                    runningDomain.destroy();

                    // and then undefine it
                    runningDomain.undefine();
                    return;
                }
            }
        } catch (LibvirtException e) {
            log.error("Error while deleting domain: " + domainName, e);
        }
    }

    private String createNetworkSpecification(String networkDevice) {

        String defaultNetworkConfig =
            "   <interface type='network'>\n" +
            "      <source network='default'/>\n" +
            "    </interface>";

        if (networkDevice == null || networkDevice.trim().length() == 0) {
            return defaultNetworkConfig;
        }

        String ethernetNetworkConfig = "" +
            "        <interface type='ethernet'>\n" +
            "            <script path=''/>\n" +
            "            <target dev='${networkDevice}'/>\n" +
//                "            <model type='virtio'/>\n" +
//                "            <address type='pci' domain='0x0000' bus='0x00' slot='0x03' function='0x0'/>\n" +
            "        </interface>";

        return ethernetNetworkConfig.replaceAll("\\$\\{networkDevice\\}", networkDevice);
    }

    private String updateTemplate(String domainName, String fileLocation, String networkDevice) {
        try {
            String templateContent = getTemplateContent();

            templateContent = templateContent
                .replaceAll("\\$\\{domainName\\}", domainName)
                .replaceAll("\\$\\{imageFile\\}", fileLocation)
                .replaceAll("\\$\\{networkInterface\\}",
                    createNetworkSpecification(networkDevice));

            return templateContent;
        } catch (IOException e) {
            log.error("Error building domain description for domain: " + domainName, e);
            return "";
        }
    }

    private String getTemplateContent() throws IOException {

        File templFile = new File(configuration.getTemplatesFolderName(), this.templateName + ".xml");

        if (templFile.exists() && templFile.isFile() && templFile.canRead()) {

            log.info("Loading template named {} from: {}",
                this.templateName, templFile.getAbsolutePath());

            return FileUtils.readFileToString(templFile);
        }

        String TEMPLATE_RESOURCES = BASIC_RESOURCE_ROOT + "/templates";

        URL templateResource =
            this.getClass().getResource(TEMPLATE_RESOURCES + "/" + this.templateName + ".xml");

        if (templateResource != null) {
            log.info("Loading template name {} from classpath resource: {}",
                this.templateName, templateResource.toExternalForm());

            return IOUtils.toString(templateResource);
        }

        throw
            new FileNotFoundException(
                String.format("Could not find template in the configured " +
                    "templates folder: %s or as a resource in the classpath: %s",
                    templFile.getParentFile().getAbsolutePath(),
                    "templates/" + this.templateName + ".xml"));
    }

    protected File buildTargetOverlayFile() {
        return
            new File(
                getConfiguration().getImagesFolderName(),
                this.domainName + "_image.qcow2");
    }

    protected int executeToolBasedCommand(String toolScript, String toolCommandLine)
        throws InterruptedException, IOException
    {

        File toolScriptFile =
            new File(
                getConfiguration().getWorkFolderName(),
                toolScript);

        return ProcessHelper
            .newProcess("" + toolScriptFile.getAbsolutePath() + " " + toolCommandLine)
            .logOutput(log, toolScript)
            .runAndWait();
    }

    protected LibvirtHandler.Configuration getConfiguration() {
        return configuration;
    }

    abstract protected String buildOverlayImage();

}
