package com.capstone.ztacs.dto;

import lombok.Data;

@Data
public class KeycloakTokenRequest {
    private String username;
    private String password;
}