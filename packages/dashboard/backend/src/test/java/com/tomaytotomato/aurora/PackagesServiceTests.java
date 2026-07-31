package com.tomaytotomato.aurora;

import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.services.PackagesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestDockerConfig.class)
class PackagesServiceTests {

  @Autowired PackagesService packages;

  @Test
  void parsesFakeRepoManifests() {
    List<Package> list = packages.list();
    assertThat(list).extracting(Package::name).contains("core", "media");
    Package media = list.stream().filter(p -> "media".equals(p.name())).findFirst().orElseThrow();
    assertThat(media.category()).isEqualTo("media");
    assertThat(media.dependsOn()).containsExactly("core");
    assertThat(media.enabled()).isTrue(); // fake-repo state has media enabled
  }
}
