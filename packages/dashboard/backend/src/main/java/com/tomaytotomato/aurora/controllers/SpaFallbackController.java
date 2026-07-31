package com.tomaytotomato.aurora.controllers;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Any GET that isn't /api/**, /actuator/**, or a real static resource falls
 * through to index.html so Vue Router owns the browser URL.
 *
 * <p>Static resources under classpath:/static/ are served automatically by
 * Spring Boot before this controller sees the request, so real assets (JS,
 * CSS, images) still 200 with the right content type.
 */
@Controller
public class SpaFallbackController {

  private final Resource index = new ClassPathResource("static/index.html");

  @GetMapping(value = {
      "/",
      "/{path:^(?!api|actuator|assets|favicon)[^.]*}",
      "/{path:^(?!api|actuator|assets|favicon)[^.]*}/**"
  })
  public ResponseEntity<Resource> forwardToIndex() {
    if (!index.exists()) {
      // Backend booted without a bundled SPA (dev). Return a friendly note.
      return ResponseEntity.status(503)
          .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
          .body(new org.springframework.core.io.ByteArrayResource(
              ("Aurora API is up but no SPA bundle was found on the classpath.\n"
                  + "In dev, run the Vue dev server and point it at http://localhost:8090/api.\n")
                  .getBytes()));
    }
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .body(index);
  }
}
