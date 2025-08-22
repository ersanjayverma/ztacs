package com.capstone.ztacs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.jsonwebtoken.Claims;

import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece; // chesslib Piece enum
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.github.bhlangonijr.chesslib.move.MoveList;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI Controller")
public class AIController {

    // UI-side piece representation — renamed to avoid clashing with chesslib Piece
    public record UiPiece(String type, String color) {}

    private static final String COLLECTION_NAME = "ztacs_memory";
    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------- General QA --------------------------

    @Operation(
        summary = "Ask AI",
        description = "Sends prompt to Ollama and returns response based on semantic similarity"
    )
    @PostMapping(value = "/ask", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> ask(HttpServletRequest request, @RequestBody String prompt) {
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
                "Identity: " + userId +
                " NAME: " + username + " Email: " + email + "\n" +
                "SYSTEM: Use the following historical context to assist your answer, but prioritize the user's current prompt.\n" +
                "Context:\n" + context + "\n\n" +
                "USER: " + prompt + "\n\n" +
                "INSTRUCTION: Focus on the user's prompt more than the context. Reply clearly and concisely.";

            // Call Ollama-compatible API
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
                // Persist memory without calling the public endpoint method directly
                persistMemory(finalPrompt);
                persistMemory(aiResponse);
                Map<String, String> responseMap = Map.of("response", aiResponse);
                return ResponseEntity.ok(responseMap);
            } else {
                return ResponseEntity.status(502).body(Map.of("response", "Error: 'response' field not found."));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("response", "Error: " + e.getMessage()));
        }
    }

    // -------------------------- Chess: Next Move --------------------------

    @PostMapping("/chessNextMove")
    public ResponseEntity<Map<String, Object>> getNextMove(@RequestBody Map<String, Object> request) {
        try {
            String fen = Objects.toString(request.get("fen"), null);
            if (fen == null || fen.isBlank()) {
                return bad("Missing 'fen'.");
            }

            @SuppressWarnings("unchecked")
            Map<String, String> lastMove = (Map<String, String>) request.get("lastMove");

            Board board = new Board();
            board.loadFromFen(fen);

            // Compute legal moves on server (UCI strings)
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
            List<String> legalUci = legalMoves.stream()
                .map(Move::toString) // chesslib Move#toString -> UCI e2e4, e7e8q, etc.
                .sorted()
                .collect(Collectors.toList());
            Set<String> legalSet = new HashSet<>(legalUci);

            String turn = fen.contains(" w ") ? "white" : "black";

            // Build strict prompt with whitelist of legal moves
            String prompt = buildPrompt(turn, fen, lastMove, legalUci);

            String raw = callAI(prompt);
            String aiMove = normalizeMove(raw);

            // If promotion omitted but required, default to queen
            if (aiMove.length() == 4 && requiresPromotion(aiMove, board)) {
                aiMove = aiMove + "q";
            }

            // Validate; if invalid, fall back to a deterministic legal move
            if (!legalSet.contains(aiMove)) {
                String alt = aiMove.length() == 4 ? aiMove + "q" : aiMove.substring(0, 4);
                if (!legalSet.contains(alt)) {
                    // Fallback: pick a legal move (simple heuristic: prefer non-king/pawn dev if possible)
                    aiMove = pickFallbackMove(board, legalMoves); // accepts Collection<Move>
                } else {
                    aiMove = alt;
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("fen", fen);
            response.put("turn", turn);
            response.put("aiMove", aiMove);       // UCI, 4 or 5 chars
            response.put("legalMoves", legalUci); // optional for debugging/client
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return bad("Error: " + e.getMessage());
        }
    }

    // ---------- chess helpers ----------

    private static String buildPrompt(String turn, String fen, Map<String, String> lastMove, List<String> legalUci) {
        String last = (lastMove != null && lastMove.get("from") != null && lastMove.get("to") != null)
            ? (lastMove.get("from") + lastMove.get("to"))
            : "none";

        // Allow 4 or 5 chars; constrain to the whitelist.
        return String.format(Locale.ROOT,
            "You are a chess engine. Analyze the position and select the best move for %s.\n" +
            "FEN: %s\n" +
            "Previous move (UCI): %s\n\n" +
            "LEGAL MOVES (UCI): %s\n\n" +
            "Return exactly one move from the provided LEGAL MOVES list, in UCI format.\n" +
            "- Use 4 chars for normal moves (e.g., e2e4).\n" +
            "- Use 5 chars for promotions (e.g., e7e8q). Choose the best promotion piece.\n" +
            "Respond with ONLY the move (no punctuation or explanation).",
            turn, fen, last, String.join(" ", legalUci));
    }

    /** Trim, lowercase, and keep only [a-h][1-8][a-h][1-8][qrbn]? */
    private static String normalizeMove(String raw) {
        String s = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        // Strip non-alphanumerics
        s = s.replaceAll("[^a-h1-8qrbn]", "");
        // Keep max 5
        if (s.length() > 5) s = s.substring(0, 5);
        // Validate shape
        if (!s.matches("^[a-h][1-8][a-h][1-8]([qrbn])?$")) return "";
        return s;
    }

    /** Detect if a 4-char move is a pawn reaching last rank (thus requires promotion). */
    private static boolean requiresPromotion(String uci4, Board board) {
        if (uci4 == null || uci4.length() != 4) return false;
        Square from = Square.fromValue(uci4.substring(0, 2).toUpperCase(Locale.ROOT));
        Square to   = Square.fromValue(uci4.substring(2, 4).toUpperCase(Locale.ROOT));
        Piece p = board.getPiece(from);
        if (p == Piece.WHITE_PAWN && to.getRank().getNotation().equals("8")) return true;
        if (p == Piece.BLACK_PAWN && to.getRank().getNotation().equals("1")) return true;
        return false;
    }

/** Simple deterministic fallback: prefer a non-king, non-pawn move if available. */
private static String pickFallbackMove(Board board, Collection<Move> legal) {
    if (legal == null || legal.isEmpty()) return "0000";

    // 1) Prefer non-king, non-pawn
    for (Move m : legal) {
        Piece mv = board.getPiece(m.getFrom());
        if (mv != Piece.WHITE_KING && mv != Piece.BLACK_KING &&
            mv != Piece.WHITE_PAWN && mv != Piece.BLACK_PAWN) {
            return m.toString();
        }
    }

    // 2) Otherwise prefer non-king
    for (Move m : legal) {
        Piece mv = board.getPiece(m.getFrom());
        if (mv != Piece.WHITE_KING && mv != Piece.BLACK_KING) {
            return m.toString();
        }
    }

    // 3) Otherwise return the first legal move
    Iterator<Move> it = legal.iterator();
    return it.hasNext() ? it.next().toString() : "0000";
}


    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    // -------------------------- Memory save endpoint --------------------------

    @PostMapping("/save")
    public ResponseEntity<String> saveMemory(@RequestBody String prompt) {
        try {
            persistMemory(prompt);
            return ResponseEntity.ok("Memory saved.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    // Internal memory persistence (used by /ask and /save)
    private void persistMemory(String prompt) throws Exception {
        float[] embedding = fetchEmbedding(prompt);

        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt", prompt);

        Map<String, Object> point = new HashMap<>();
        point.put("id", UUID.randomUUID().toString()); // store as string
        point.put("payload", payload);
        point.put("vector", embedding);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("points", List.of(point));

        // Use PUT to upsert points
        sendPut("https://vector.blackhatbadshah.com/collections/" + COLLECTION_NAME + "/points?wait=true", requestBody);
    }

    // -------------------------- Vector / Embeddings utilities --------------------------

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
        conn.setRequestMethod("PUT");
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

    // -------------------------- Auth helpers --------------------------

    @SuppressWarnings("unchecked")
    public Claims getClaimsFromRequest(HttpServletRequest request) {
        return (Claims) request.getAttribute("claims");
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

    // -------------------------- Optional UI helpers (FEN build from UI board) --------------------------

    // Determine whose turn it is (UI board, optional helper)
    private String getCurrentTurn(UiPiece[][] board, Map<String, String> lastMove) {
        return (lastMove == null || lastMove.get("to") == null) ? "white" : "black";
    }

    // Convert UI piece to FEN character
    private char toFENChar(UiPiece piece) {
        Map<String, Character> map = Map.of(
            "pawn", 'p',
            "rook", 'r',
            "knight", 'n',
            "bishop", 'b',
            "queen", 'q',
            "king", 'k'
        );
        char c = map.getOrDefault(piece.type(), '?');
        return "white".equals(piece.color()) ? Character.toUpperCase(c) : c;
    }

    private String buildFEN(UiPiece[][] board, String turn) {
        StringBuilder fen = new StringBuilder();

        for (int row = 0; row < 8; row++) {
            int empty = 0;
            for (int col = 0; col < 8; col++) {
                UiPiece piece = board[row][col];
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

        return fen + " " + ("white".equals(turn) ? "w" : "b") + " - - 0 1";
    }
}
