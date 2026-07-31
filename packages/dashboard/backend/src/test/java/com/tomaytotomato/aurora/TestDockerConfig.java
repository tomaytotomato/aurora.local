package com.tomaytotomato.aurora;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.VersionCmd;
import com.github.dockerjava.api.model.Version;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the real DockerClient bean during tests so nothing tries to open
 * /var/run/docker.sock.
 */
@TestConfiguration
public class TestDockerConfig {

  @Bean
  @Primary
  public DockerClient dockerClient() {
    DockerClient mock = Mockito.mock(DockerClient.class, Mockito.RETURNS_DEEP_STUBS);
    // versionCmd().exec() -> Version with a bogus but non-null version.
    VersionCmd vc = Mockito.mock(VersionCmd.class);
    Version v = new Version();
    Mockito.when(vc.exec()).thenReturn(v);
    Mockito.when(mock.versionCmd()).thenReturn(vc);
    // listContainersCmd() chain returns an empty list by default via deep stubs.
    return mock;
  }
}
