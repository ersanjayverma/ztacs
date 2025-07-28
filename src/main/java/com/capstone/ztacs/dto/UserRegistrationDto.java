package com.capstone.ztacs.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRegistrationDto {
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String password;
}
