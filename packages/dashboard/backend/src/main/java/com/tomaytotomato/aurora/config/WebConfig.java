package com.tomaytotomato.aurora.config;

import com.tomaytotomato.aurora.controllers.SpaFallbackController;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA fallback is done via {@link SpaFallbackController} which owns a broad
 * mapping. This class stays as a placeholder for future resource handlers
 * (cache headers, resource chains, …).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
