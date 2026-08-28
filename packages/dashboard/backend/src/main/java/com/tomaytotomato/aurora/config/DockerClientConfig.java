package com.tomaytotomato.aurora.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({AuroraProperties.class, MarketplaceProperties.class})
public class DockerClientConfig {

  @Bean
  public DockerClient dockerClient(AuroraProperties props) {
    var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(props.docker().host())
        .build();
    // P2 #4: bound socket calls so a hung dockerd cannot bleed the
    // ForkJoinPool that StatusProbeService dispatches probes onto.
    // 2s connect / 3s read is well above the ~50ms typical for a local
    // socket and well below the 5s status-probe HTTP timeout upstream.
    var httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(URI.create(config.getDockerHost().toString()))
        .maxConnections(20)
        .connectionTimeout(Duration.ofSeconds(2))
        .responseTimeout(Duration.ofSeconds(3))
        .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }
}
