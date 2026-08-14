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
 *
 * <p>Named {@code testDockerClient()} rather than {@code dockerClient()}
 * deliberately: {@code spring.main.allow-bean-definition-overriding} is
 * true for tests, and a same-named {@code @Bean} method doesn't get
 * disambiguated by {@link Primary} at all — it silently replaces
 * whichever bean definition of that name was registered first, which
 * depends on configuration-class processing order rather than on
 * {@code @Primary}. A distinct name turns this into a genuine "two
 * candidates, one primary" case, which {@code @Autowired DockerClient}
 * resolves deterministically regardless of import order.
 */
@TestConfiguration
public class TestDockerConfig {

  @Bean
  @Primary
  public DockerClient testDockerClient() {
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
