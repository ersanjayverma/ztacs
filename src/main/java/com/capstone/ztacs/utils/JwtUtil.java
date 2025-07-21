package com.capstone.ztacs.util;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.URL;
import java.util.Map;

public class JwtUtil {

    private static final String JWKS_URL = "https://auth.blackhatbadshah.com/realms/ztacs/protocol/openid-connect/certs";

    private static final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    static {
        try {
            jwtProcessor = new DefaultJWTProcessor<>();
            JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(new URL(JWKS_URL));
            JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
            jwtProcessor.setJWSKeySelector(keySelector);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JWT processor", e);
        }
    }

    public static Claims validateToken(String token) throws Exception {
        SignedJWT signedJWT = SignedJWT.parse(token.replace("Bearer ", "").trim());
        JWTClaimsSet claimsSet = jwtProcessor.process(signedJWT, null);
        Map<String, Object> claimsMap = claimsSet.getClaims();
        return new DefaultClaims(claimsMap); // JJWT Claims
    }
}
