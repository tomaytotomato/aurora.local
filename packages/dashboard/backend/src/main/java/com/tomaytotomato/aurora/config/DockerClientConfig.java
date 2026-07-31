package com.tomaytotomato.aurora.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(AuroraProperties.class)
public class DockerClientConfig {

  @Bean
  public DockerClient dockerClient(AuroraProperties props) {
    var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(props.docker().host())
        .build();
    var httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(URI.create(config.getDockerHost().toString()))
        .maxConnections(20)
        .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }
}
