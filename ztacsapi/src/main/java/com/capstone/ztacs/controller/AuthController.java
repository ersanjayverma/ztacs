package com.capstone.ztacsapi.controller;

import com.capstone.ztacs.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import com.capstone.ztacs.service.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth Controller")
public class AuthController {

    private final RestTemplate restTemplate;
    private final Environment env;
    private final KeycloakAdminService keycloakAdminService;
    @Autowired
    public AuthController(RestTemplate restTemplate, Environment env,KeycloakAdminService keycloakAdminService) {
        this.restTemplate = restTemplate;
        this.env = env;
        this.keycloakAdminService=keycloakAdminService;
    }

    @Operation(
        summary = "Get JWT token from Keycloak",
        description = "Authenticates with Keycloak and returns JWT token"
    )
    @PostMapping("/get-token")
    public ResponseEntity<?> getToken(@RequestBody KeycloakTokenRequest request) {
        String domain = env.getProperty("auth0.domain");
        String clientId = env.getProperty("auth0.clientId");
        String clientSecret = env.getProperty("auth0.clientSecret");

        if (domain == null || clientId == null || clientSecret == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Keycloak configuration is missing in application.properties.");
        }

        String tokenEndpoint = domain + "protocol/openid-connect/token";

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "password");
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        formData.add("username", request.getUsername());
        formData.add("password", request.getPassword());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(formData, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(tokenEndpoint, entity, String.class);
            return ResponseEntity.ok(response.getBody());

        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getRawStatusCode())
                .body("Keycloak Error: " + e.getResponseBodyAsString());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Unexpected error: " + e.getMessage());
        }

    }

    @GetMapping("/ping")
    @Operation(summary = "Ping endpoint", description = "Just returns OK")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Auth service is up");
    }
    @PostMapping("/register")
    @Operation(summary = "Register user with Keycloak", description = "Just returns OK")
    public ResponseEntity<String> registerUser(@RequestBody UserRegistrationDto dto) {
        keycloakAdminService.registerUser(dto);
        return ResponseEntity.ok("User registered successfully");
    }
    @PostMapping("/refresh")
     @Operation(
        summary = "Get JWT token from Keycloak using refresh token",
        description = "Authenticates with Keycloak and returns JWT token"
    )
    public ResponseEntity<TokenResponse> refreshToken(@RequestBody TokenRefreshRequest request) {
        TokenResponse response = keycloakAdminService.refreshAccessToken(request);
        return ResponseEntity.ok(response);
    }

}
