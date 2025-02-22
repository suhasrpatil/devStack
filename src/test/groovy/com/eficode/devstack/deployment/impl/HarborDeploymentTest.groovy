package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.container.impl.HarborManagerContainer
import de.gesellix.docker.remote.api.ContainerState
import de.gesellix.docker.remote.api.ContainerSummary
import kong.unirest.Unirest
import org.slf4j.LoggerFactory

class HarborDeploymentTest extends DevStackSpec {
    def setupSpec() {

        //dockerRemoteHost = "https://docker.domain.se:2376"
        //dockerCertPath = "~/.docker/"


        log = LoggerFactory.getLogger(HarborDeploymentTest.class)

        cleanupContainerPorts = [80]

        disableCleanup = false
    }


    @Override
    ArrayList<String> getCleanupContainerNames() {


        ArrayList<ContainerSummary> containers = dockerClient.ps().content

        ArrayList<String> containerNames = containers.findAll { it.image.startsWith("goharbor") }.collect { it.names.first().replaceFirst("/", "") }
        containerNames.add(HarborManagerContainer.extractDomainFromUrl(specificationContext?.currentIteration?.dataVariables?.harborBaseUrl as String) + "-manager")
        return containerNames

    }

    def "Test the basics"(String dockerHost, String certPath, String harborBaseUrl, String harborVersion, String harborBaseDir) {

        when:
        HarborDeployment hd = new HarborDeployment(harborBaseUrl, harborVersion, harborBaseDir, dockerHost, certPath)

        if (dockerHost && certPath) {
            assert hd.managerContainer.dockerClient.dockerClientConfig.host == hd.managerContainer.extractDomainFromUrl(dockerHost): "Connection to remote Docker host was not setup"

        }else {
            assert hd.managerContainer.dockerClient.dockerClientConfig.host == "/var/run/docker.sock": "Connection to local Docker host was not setup"

        }

        hd.setupDeployment()

        //hd.managerContainer.runAfterDockerSetup()


        then:
        Unirest.get(harborBaseUrl).basicAuth("admin", "Harbor12345").asEmpty().status == 200
        hd.harborContainers.id.contains(hd.managerContainer.inspectContainer().id) //Make sure harborContainers returns manager container as well
        hd.harborContainers.every { it.state in [ContainerState.Status.Running.value, ContainerState.Status.Restarting.value] }
        hd.harborContainers.every {it.networkSettings.networks.keySet().toList() == [hd.deploymentNetworkName]}
        hd.harborContainers.collect {it.names.first()}.every {containerName ->
            String hostname = containerName[1..-1]
            hd.managerContainer.runBashCommandInContainer("ping ${hostname} -c 1 && echo Status: \$?", 5).last().contains("Status: 0")
        }

        when: "Stopping the deployment"
        hd.stopDeployment()

        then: "All containers should remain but have status Exited"
        hd.harborContainers.every { it.state == ContainerState.Status.Exited.value }

        when: "Staring the deployment"
        ArrayList<String> containerIdsBeforeStart = hd.getHarborContainers().id
        hd.startDeployment()

        then: "All containers should have the same IDs and status Running"
        containerIdsBeforeStart.sort() == hd.getHarborContainers().id.sort()
        hd.getContainers().every { it.status() == ContainerState.Status.Running }
        Unirest.get(harborBaseUrl).basicAuth("admin", "Harbor12345").asEmpty().status == 200
        log.info("\tSuccessful harbor Web login! ")

        when:
        hd.stopAndRemoveDeployment()

        then:
        hd.getHarborContainers().size() == 0

        where:
        dockerHost       | certPath       | harborBaseUrl             | harborVersion | harborBaseDir
        ""               | ""             | "http://localhost"        | "v2.6.0"      | "/tmp"
        dockerRemoteHost | dockerCertPath | "http://harbor.domain.se" | "v2.6.0"      | "/tmp"

    }
}
