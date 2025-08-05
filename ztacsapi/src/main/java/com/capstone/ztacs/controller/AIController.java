package com.capstone.ztacs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI Controller")
public class AIController {

    @Operation(
        summary = "Ask AI",
        description = "Sends prompt to Ollama and returns full response"
    )
    @PostMapping(value = "/ask", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ask(@RequestBody String prompt) {
        try {
            URL url = new URL("https://ai.blackhatbadshah.com/api/generate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            // Build request body
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> body = new HashMap<>();
            body.put("model", "Blackhatbadshah");
            body.put("stream", false);  // Non-streaming
            body.put("prompt", prompt);

            String json = mapper.writeValueAsString(body);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes());
                os.flush();
            }

            // Read response
            StringBuilder rawResponse = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    rawResponse.append(line);
                }
            }

            JsonNode node = mapper.readTree(rawResponse.toString());

            if (node.has("response")) {
                String finalOutput = node.get("response").asText();
                return ResponseEntity.ok().body(finalOutput);
            } else {
                return ResponseEntity.status(502).body("Error: 'response' field not found.");
            }

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
