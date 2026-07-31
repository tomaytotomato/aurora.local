package com.tomaytotomato.aurora;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestDockerConfig.class)
class AuroraApplicationTests {

  @Test
  void contextLoads() {
    // The point is that Spring can wire everything.
  }
}
