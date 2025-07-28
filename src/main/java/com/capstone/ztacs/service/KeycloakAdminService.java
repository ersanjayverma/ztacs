package com.capstone.ztacs.service;

import com.capstone.ztacs.dto.UserRegistrationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.*;

@Service
@Slf4j
public class KeycloakAdminService {

    @Value("${auth0.domain}")
    private String keycloakDomain;

    @Value("${auth0.clientId}")
    private String clientId;

    @Value("${auth0.clientSecret}")
    private String clientSecret;
  
    private final RestTemplate restTemplate ;

    public KeycloakAdminService(RestTemplate restTemplate){
        this.restTemplate=restTemplate;
    }

    private String getAdminToken() {
    String tokenUrl = keycloakDomain + "protocol/openid-connect/token";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);

    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);

    ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, Map.class);

    return response.getBody() != null ? (String) response.getBody().get("access_token") : null;
}

    public void registerUser(UserRegistrationDto dto) {
        String token = getAdminToken();
        if (token == null) throw new RuntimeException("Failed to fetch Keycloak token");

        String createUserUrl = keycloakDomain.replace("/realms/", "/admin/realms/") + "users";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("enabled", true);
        userPayload.put("username", dto.getUsername());
        userPayload.put("email", dto.getEmail());
        userPayload.put("firstName", dto.getFirstName());
        userPayload.put("lastName", dto.getLastName());

        Map<String, Object> credential = new HashMap<>();
        credential.put("type", "password");
        credential.put("value", dto.getPassword());
        credential.put("temporary", false);

        userPayload.put("credentials", List.of(credential));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(userPayload, headers);
        ResponseEntity<Void> response = restTemplate.postForEntity(createUserUrl, entity, Void.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("User created: {}", dto.getUsername());
        } else if (response.getStatusCode().value() == 409) {
            throw new RuntimeException("User already exists");
        } else {
            throw new RuntimeException("Failed to register user in Keycloak. Status: " + response.getStatusCode());
        }
    }
}
