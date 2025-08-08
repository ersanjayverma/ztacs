package com.capstone.ztacs.config;

import com.capstone.ztacs.security.JwtInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")  // secure these endpoints
                .excludePathPatterns(
                    "/api/auth/**",                 // ✅ Exclude auth endpoints
                    "/swagger-ui.html",            // ✅ Sometimes needed separately
                    "/swagger-ui/**",
                    "/v3/api-docs/**",             // ✅ Full path
                    "/swagger-resources/**",
                    "/webjars/**"                  // If using webjars
                );
    }
        @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*") // Frontend URL
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
