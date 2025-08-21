package com.capstone.ztacs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.io.InputStream;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI Controller")
public class AIController {
    public record Piece(String type, String color) {}
    private static final String COLLECTION_NAME = "ztacs_memory";
    private final ObjectMapper mapper = new ObjectMapper();

    @Operation(
    summary = "Ask AI",
    description = "Sends prompt to Ollama and returns response based on semantic similarity"
)

@PostMapping(value = "/ask", produces = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<Map<String, String>> ask(HttpServletRequest request,@RequestBody String prompt) {
    try {

        Claims claims = getClaimsFromRequest(request);
        String userId = claims.getSubject(); // "sub"
        String username = (String) claims.get("preferred_username");
        String email = (String) claims.get("email");


        float[] embedding = fetchEmbedding(prompt);

        // Search top 5 similar prompts from Qdrant
        List<String> similarHistory = searchQdrant(embedding);
        
        String context = String.join("\n", similarHistory);
String finalPrompt = 
                "Identity: "+userId+
                "NAME: "+username + "Email: "+email+
                "SYSTEM: Use the following historical context to assist your answer, but prioritize the user's current prompt.\n" +
                "Context:\n" + context + "\n\n" +
                "USER: " + prompt + "\n\n" +
                "INSTRUCTION: Focus on the user's prompt more than the context. reply clearly and concisely.";

        // Call Ollama API
        URL url = new URL("https://ai.blackhatbadshah.com/api/generate");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        Map<String, Object> body = new HashMap<>();
        body.put("model", "Blackhatbadshah");
        body.put("stream", false);
        body.put("prompt", finalPrompt);

        String json = mapper.writeValueAsString(body);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes());
            os.flush();
        }

        StringBuilder rawResponse = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawResponse.append(line);
            }
        }

        JsonNode node = mapper.readTree(rawResponse.toString());

        if (node.has("response")) {
            String aiResponse = node.get("response").asText();
            saveMemory(finalPrompt);
            saveMemory(aiResponse);
            Map<String, String> responseMap = Map.of("response", aiResponse);
            return ResponseEntity.ok(responseMap);
        } else {
            return ResponseEntity.status(502).body(Map.of("response", "Error: 'response' field not found."));
        }

    } catch (Exception e) {
        return ResponseEntity.internalServerError().body(Map.of("response", "Error: " + e.getMessage()));
    }
}


   @PostMapping("/save")
public ResponseEntity<String> saveMemory(@RequestBody String prompt) {
    try {
        float[] embedding = fetchEmbedding(prompt);

        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt", prompt);

        Map<String, Object> point = new HashMap<>();
        point.put("id", UUID.randomUUID().toString()); // ✅ as string
        point.put("payload", payload);
        point.put("vector", embedding); // Ensure vector matches expected dimensions

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("points", List.of(point));

        // ✅ Use PUT method to match curl example
        sendPut("https://vector.blackhatbadshah.com/collections/" + COLLECTION_NAME + "/points?wait=true", requestBody);

        return ResponseEntity.ok("Memory saved.");
    } catch (Exception e) {
        return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
    }
}


    private List<String> searchQdrant(float[] embedding) throws Exception {
        Map<String, Object> searchBody = new HashMap<>();
        searchBody.put("vector", embedding);
        searchBody.put("top", 5);
        searchBody.put("with_payload", true);

        JsonNode response = sendPost("https://vector.blackhatbadshah.com/collections/" + COLLECTION_NAME + "/points/search", searchBody);

        List<String> results = new ArrayList<>();
        for (JsonNode point : response.get("result")) {
            JsonNode payload = point.get("payload");
            if (payload != null && payload.has("prompt")) {
                results.add(payload.get("prompt").asText());
            }
        }
        return results;
    }

    private float[] fetchEmbedding(String text) throws Exception {
        URL url = new URL("https://ai.blackhatbadshah.com/api/embeddings");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        Map<String, Object> body = new HashMap<>();
        body.put("model", "nomic-embed-text");
        body.put("prompt", text);

        String json = mapper.writeValueAsString(body);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes());
            os.flush();
        }

        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line);
            }
        }

        JsonNode node = mapper.readTree(raw.toString());
        if (node.has("embedding")) {
            JsonNode embedNode = node.get("embedding");
            float[] vector = new float[embedNode.size()];
            for (int i = 0; i < embedNode.size(); i++) {
                vector[i] = (float) embedNode.get(i).asDouble();
            }
            return vector;
        }
        throw new RuntimeException("Invalid embedding response");
    }

    private JsonNode sendPost(String urlString, Map<String, Object> body) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        String json = mapper.writeValueAsString(body);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes());
            os.flush();
        }
        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line);
            }
        }

        JsonNode response = mapper.readTree(raw.toString());

        if (status < 200 || status >= 300) {
            throw new RuntimeException("HTTP " + status + ": " + response.toPrettyString());
        }

        return response;
    }
    private JsonNode sendPut(String urlString, Map<String, Object> body) throws Exception {
    URL url = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("PUT"); // ✅ Match curl
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");

    String json = mapper.writeValueAsString(body);

    try (OutputStream os = conn.getOutputStream()) {
        os.write(json.getBytes());
        os.flush();
    }

    int responseCode = conn.getResponseCode();
    InputStream inputStream = (responseCode < 400) ? conn.getInputStream() : conn.getErrorStream();

    StringBuilder raw = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
        String line;
        while ((line = reader.readLine()) != null) {
            raw.append(line);
        }
    }

    return mapper.readTree(raw.toString());
}
@SuppressWarnings("unchecked")
public Claims getClaimsFromRequest(HttpServletRequest request) {
    return (Claims) request.getAttribute("claims");
}

@PostMapping("chessNextMove")
public ResponseEntity<Map<String, Object>> getNextMove(@RequestBody Map<String, Object> request) {
    try {
        List<Map<String, Object>> pieces = (List<Map<String, Object>>) request.get("pieces");
        Map<String, String> lastMove = (Map<String, String>) request.get("lastMove");

        // Build board array
        Piece[][] board = new Piece[8][8];
        for (Map<String, Object> pieceData : pieces) {
            Map<String, String> pieceInfo = (Map<String, String>) pieceData.get("piece");
            int row = (int) pieceData.get("row");
            int col = (int) pieceData.get("col");

            board[row][col] = new Piece(pieceInfo.get("type"), pieceInfo.get("color"));
        }

        String turn = getCurrentTurn(board, lastMove);
        String fen = buildFEN(board, turn);

        //  Prompt AI for best move
        String prompt = String.format(
            "You are a chess engine. Analyze this board position and suggest the best move for %s.\n" +
            "FEN: %s\n\n" +
            "Reply ONLY with the move in algebraic notation (like e2e4 or g1f3), nothing else.",
            turn,
            fen
        );

        String aiMove = callAI(prompt);

        Map<String, Object> response = new HashMap<>();
        response.put("fen", fen);
        response.put("turn", turn);
        response.put("aiMove", aiMove.trim());

        return ResponseEntity.ok(response);

    } catch (Exception e) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", "Error: " + e.getMessage()
        ));
    }
}
private String callAI(String prompt) throws Exception {
    URL url = new URL("https://ai.blackhatbadshah.com/api/generate");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");

    Map<String, Object> body = new HashMap<>();
    body.put("model", "Blackhatbadshah");
    body.put("stream", false);
    body.put("prompt", prompt);

    String json = mapper.writeValueAsString(body);

    try (OutputStream os = conn.getOutputStream()) {
        os.write(json.getBytes());
        os.flush();
    }

    StringBuilder raw = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
            raw.append(line);
        }
    }

    JsonNode node = mapper.readTree(raw.toString());

    if (node.has("response")) {
        return node.get("response").asText();
    } else {
        throw new RuntimeException("AI response missing 'response' field.");
    }
}
// Determine whose turn it is
private String getCurrentTurn(Piece[][] board, Map<String, String> lastMove) {
    return (lastMove == null || lastMove.get("to") == null) ? "white" : "black";
}
// Convert piece to FEN character
private char toFENChar(Piece piece) {
    Map<String, Character> map = Map.of(
        "pawn", 'p',
        "rook", 'r',
        "knight", 'n',
        "bishop", 'b',
        "queen", 'q',
        "king", 'k'
    );
    char c = map.getOrDefault(piece.type(), '?');
    return piece.color().equals("white") ? Character.toUpperCase(c) : c;
}

private String buildFEN(AIController.Piece[][] board, String turn) {
    StringBuilder fen = new StringBuilder();

    for (int row = 0; row < 8; row++) {
        int empty = 0;
        for (int col = 0; col < 8; col++) {
            AIController.Piece piece = board[row][col];
            if (piece == null) {
                empty++;
            } else {
                if (empty > 0) {
                    fen.append(empty);
                    empty = 0;
                }
                fen.append(toFENChar(piece));
            }
        }
        if (empty > 0) {
            fen.append(empty);
        }
        if (row < 7) {
            fen.append('/');
        }
    }

    return fen + " " + (turn.equals("white") ? "w" : "b") + " - - 0 1";
}



}


