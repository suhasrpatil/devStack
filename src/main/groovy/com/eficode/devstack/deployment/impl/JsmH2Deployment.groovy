package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.JsmContainer
import com.eficode.devstack.deployment.Deployment
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Sets up a JSM deployment,
 * creates a local H2 database,
 * configures JSM license and admin account (admin/admin)
 * installs optional marketplace apps and their licenses.
 *
 *
 * Before running setupDeployment() make sure to setJiraLicense() and optionally populate setAppsToInstall (ex: [https:marketplace....:AAbbv1223cc...])
 *
 * Container name will be derived from the supplied jiraBaseUrl, but can be overridden before creation with: jsmH2Deployment.jsmContainer.containerName = "Something"
 */

class JsmH2Deployment implements Deployment{

    String friendlyName = "JIRA H2 Deployment"
    Logger log = LoggerFactory.getLogger(this.class)
    JiraInstanceManagerRest jiraRest
    ArrayList<Container> containers = []
    Map<String,String> appsToInstall = [:]
    String jiraLicense

    String jiraBaseUrl

    /**
     * Sets up a JSM Deployment
     * @param jiraBaseUrl The full base url where JIRA should be reached (ex: http://jira.domain.se:8080)
     * @param dockerHost An optional docker host, when not using local Docker Engine (ex: https://docker.domain.se:2376)
     * @param dockerCertPath folder containing ca.pem, and the client cert: cert.pem, key.pem (ex: ~/.docker/")
     */
    JsmH2Deployment(String jiraBaseUrl, String dockerHost = "", String dockerCertPath = "") {
        this.jiraBaseUrl = jiraBaseUrl
        this.jiraRest = new JiraInstanceManagerRest(jiraBaseUrl)
        this.containers = [new JsmContainer(dockerHost, dockerCertPath)]
        jsmContainer.containerMainPort = jsmContainer.extractPortFromUrl(jiraBaseUrl)
        jsmContainer.containerName = jsmContainer.extractDomainFromUrl(jiraBaseUrl)
    }

    JsmContainer getJsmContainer() {
        return containers.find {it instanceof JsmContainer} as JsmContainer
    }

    String getJsmContainerId() {
        return jsmContainer.id
    }



    void setJiraLicense(File licenseFile) {
        jiraLicense = licenseFile.text
    }

    void setJiraLicense(String licenseText) {
        jiraLicense = licenseText
    }

    /**
     * Install apps in to JIRA
     * @param appsAndLicenses key = App url (from marketplace), value = license string (optional)
     * @return true if no apps where installed, or apps where installed successfully
     */
    boolean installApps(Map<String,String> appsAndLicenses = appsToInstall) {

        if (appsAndLicenses) {
            log.info("Installing ${appsAndLicenses.size()} app(s)")
            appsAndLicenses.each {url, license ->
                assert jiraRest.installApp(url, license) : "Error installing app:" + url
            }
        }

        return true

    }

    /**
     * Uploads multiple new, or updates existing script files on the JIRA server
     * @param srcDest A map where the key is a source file or folder, and value is destination file or folder, ex:
     *     <p>"../src/someDir/someSubPath/"             :   "someDir/someSubPath/"
     *     <p>"../src/somOtherDir/SomeScript.groovy"    :   "somOtherDir/SomeScript.groovy"
     *
     * @return true on success
     */
    boolean updateScriptrunnerFiles(Map<String,String>srcDest) {
        jiraRest.updateScriptrunnerFiles(srcDest)
    }

    boolean setupDeployment() {
        log.info("Setting up deployment:" + friendlyName)

        assert jiraLicense : "Error no Jira License has been setup"

        jsmContainer.containerDefaultNetworks = [deploymentNetworkName]
        jsmContainer.createContainer()
        log.info("\tCreated jsm container:" + jsmContainer.id)

        assert jsmContainer.startContainer() : "Error starting JSM container:" + jsmContainer.id
        log.info("\tStarted JSM container")

        log.info("\tCreating jira-config.properties")
        String cmdJiraConfigProperties = "echo \"jira.websudo.is.disabled=true\" >> jira-config.properties; chown jira:jira jira-config.properties && echo status: \$?"
        assert jsmContainer.runBashCommandInContainer(cmdJiraConfigProperties).find {it == "status: 0"} : "Error creating jira-config.properties file"

        log.info("\tSetting up local H2 database")
        assert jiraRest.setupH2Database() : "Error setting up H2 database for JSM"
        log.info("\t\tDatabase setup successfully")
        log.info("\tSetting up application properties and Jira license")
        assert jiraRest.setApplicationProperties(jiraLicense, "JIRA", jiraBaseUrl)
        log.info("\t\tLicense and properties setup successfully")

        if (jiraRest.setupUserBasicPref()) {
            log.info("\tSetup defaults for user ${jiraRest.adminUsername} and removed pop-ups")
        }else {
            log.warn("\tThere was a problem setting defaults for user ${jiraRest.adminUsername} and removing pop-ups")
        }

       if(appsToInstall) {
           installApps()
       }


        log.info("\tJSM deployment finished, you should now be able to login")
        log.info("\t\tUrl:" + jiraBaseUrl)
        log.info("\t\tUsername:" + jiraRest.adminUsername)
        log.info("\t\tPassword:" + jiraRest.adminPassword)

        return true
    }




}
