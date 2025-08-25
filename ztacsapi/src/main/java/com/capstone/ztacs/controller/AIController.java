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
import java.util.stream.Collectors;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI Controller")
public class AIController {

    public record UiPiece(String type, String color) {}

    private static final String COLLECTION_NAME = "ztacs_memory";
    private final ObjectMapper mapper = new ObjectMapper();

    // ======== Chat + Memory ========

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

            List<String> similarHistory = searchQdrant(embedding);
            String context = String.join("\n", similarHistory);

            String finalPrompt =
                "IDENTITY:\n" +
                "  UserID: " + userId + "\n" +
                "  Name: " + username + "\n" +
                "  Email: " + email + "\n\n" +

                "SYSTEM:\n" +
                "  Use the historical context to assist your answer, but prioritize the user's current prompt.\n\n" +

                "CONTEXT:\n" + context + "\n\n" +

                "USER PROMPT:\n" + prompt + "\n\n" +

                "INSTRUCTION:\n" +
                "  Focus on the user's prompt more than the context.\n" +
                "  Reply clearly, concisely, and directly.";

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

    // ======== Chess Next Move (with learning) ========

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

            List<Move> legalMovesObj = MoveGenerator.generateLegalMoves(board);
            if (legalMovesObj == null || legalMovesObj.isEmpty()) {
                return bad("No legal moves available.");
            }

            List<String> legalUci = legalMovesObj.stream()
                    .map(Move::toString)
                    .sorted()
                    .collect(Collectors.toList());
            Set<String> legalSet = new HashSet<>(legalUci);

            String turn = fen.contains(" w ") ? "white" : "black";

            String prompt = buildPrompt(turn, fen, lastMove, legalUci);

            String raw = callAI(prompt);
            String aiMove = normalizeMove(raw);    // "" if invalid

            if (aiMove != null && aiMove.length() == 4 && requiresPromotion(aiMove, board)) {
                aiMove = aiMove + "q";
            }

            // Unified selection: policy scores everything and UPDATES WEIGHTS every time.
            String finalMoveUci = chooseMoveUci(aiMove, legalSet, legalMovesObj, board);

            Map<String, Object> response = new HashMap<>();
            response.put("fen", fen);
            response.put("turn", turn);
            response.put("aiMove", finalMoveUci);   // FINAL move (UCI)
            response.put("legalMoves", legalUci);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return bad("Error: " + e.getMessage());
        }
    }

    /**
     * Policy-based chooser that learns every move.
     * - If AI suggestion is legal, it gets a small bonus but still competes with policy score.
     * - Updates weights (online) toward the chosen move. Optional negative sampling included.
     */
    private String chooseMoveUci(
            String aiMoveUci,
            Set<String> legalSet,
            List<Move> legalMovesObj,
            Board board) {

        // Normalize AI suggestion to legal or null
        String ai = (aiMoveUci != null && legalSet.contains(aiMoveUci)) ? aiMoveUci : null;

        double[] w = loadChessPolicyWeights();
        String bestUci = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        // Score all legal moves
        for (Move m : legalMovesObj) {
            double[] x = featurizeMove(board, m);
            double s = dot(w, x);
            if (ai != null && m.toString().equals(ai)) s += 0.15; // small bias toward AI's suggestion
            if (s > bestScore) {
                bestScore = s;
                bestUci = m.toString();
            }
            try { logMoveExampleToQdrant(board, m, x, s); } catch (Exception ignore) {}
        }

        if (bestUci == null) {
            // Should not happen; fallback to policy picker (also learns)
            return pickPolicyFallbackAndLearn(board, legalMovesObj);
        }

        // Online update toward chosen move
        Move chosen = findMoveByUci(legalMovesObj, bestUci);
        double[] xChosen = featurizeMove(board, chosen);
        double[] w2 = Arrays.copyOf(w, w.length);
        double lr = 0.01; // stronger than 0.001

        for (int i = 0; i < w2.length; i++) w2[i] += lr * xChosen[i];

        // Optional: small negative sampling on a couple of non-chosen moves
        int negatives = 2;
        for (Move m : legalMovesObj) {
            if (negatives == 0) break;
            if (m.toString().equals(bestUci)) continue;
            double[] x = featurizeMove(board, m);
            for (int i = 0; i < w2.length; i++) w2[i] -= (lr * 0.5) * x[i];
            negatives--;
        }

        try { saveChessPolicyWeights(w2); } catch (Exception ignore) {}

        return bestUci;
    }

    /** Policy fallback that still updates weights (used if bestUci somehow null). */
    private String pickPolicyFallbackAndLearn(Board board, Collection<Move> legal) {
        if (legal == null || legal.isEmpty()) return "0000";

        double[] w = loadChessPolicyWeights();
        String bestUci = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Move m : legal) {
            double[] x = featurizeMove(board, m);
            double s = dot(w, x);
            if (s > bestScore) { bestScore = s; bestUci = m.toString(); }
            try { logMoveExampleToQdrant(board, m, x, s); } catch (Exception ignore) {}
        }

        if (bestUci != null) {
            Move best = findMoveByUci(legal, bestUci);
            double[] xbest = featurizeMove(board, best);
            double lr = 0.01;
            for (int i = 0; i < w.length; i++) w[i] += lr * xbest[i];
            try { saveChessPolicyWeights(w); } catch (Exception ignore) {}
            return bestUci;
        }
        return legal.iterator().next().toString();
    }

    private Move findMoveByUci(Collection<Move> legal, String uci) {
        for (Move m : legal) if (m.toString().equals(uci)) return m;
        throw new IllegalArgumentException("Move not in legal set: " + uci);
    }

    // ======== Prompting + Normalization ========

    private String buildPrompt(
            String turn,
            String fen,
            Map<String, String> lastMove,
            List<String> legalUci) throws Exception {

        String last = (lastMove != null && lastMove.get("from") != null && lastMove.get("to") != null)
            ? (lastMove.get("from") + lastMove.get("to"))
            : "none";

        String contextText = String.format("Turn: %s | FEN: %s | Last: %s", turn, fen, last);

        float[] embedding = fetchEmbedding(contextText);
        List<String> similarPrompts = searchQdrant(embedding);

        String memorySection = similarPrompts.isEmpty()
            ? "No similar past positions found."
            : String.join("\n- ", similarPrompts);

        return String.format(Locale.ROOT,
            "You are a chess engine. Analyze the position and select the best move for %s.\n" +
            "FEN: %s\n" +
            "Previous move (UCI): %s\n\n" +
            "LEGAL MOVES (UCI): %s\n\n" +
            "Relevant past situations:\n- %s\n\n" +
            "Return exactly one move from the provided LEGAL MOVES list, in UCI format.\n" +
            "- Use 4 chars for normal moves (e.g., e2e4).\n" +
            "- Use 5 chars for promotions (e.g., e7e8q). Choose the best promotion piece.\n" +
            "Respond with ONLY the move (no punctuation or explanation).",
            turn, fen, last, String.join(" ", legalUci), memorySection);
    }

    private String normalizeMove(String raw) {
        String s = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-h1-8qrbn]", "");
        if (s.length() > 5) s = s.substring(0, 5);
        if (!s.matches("^[a-h][1-8][a-h][1-8]([qrbn])?$")) return "";
        return s;
    }

    private boolean requiresPromotion(String uci4, Board board) {
        if (uci4 == null || uci4.length() != 4) return false;
        Square from = Square.fromValue(uci4.substring(0, 2).toUpperCase(Locale.ROOT));
        Square to   = Square.fromValue(uci4.substring(2, 4).toUpperCase(Locale.ROOT));
        Piece p = board.getPiece(from);
        if (p == Piece.WHITE_PAWN && to.getRank().getNotation().equals("8")) return true;
        if (p == Piece.BLACK_PAWN && to.getRank().getNotation().equals("1")) return true;
        return false;
    }

    // ======== HTTP helpers ========

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @PostMapping("/save")
    public ResponseEntity<String> saveMemory(@RequestBody String prompt) {
        try {
            persistMemory(prompt);
            return ResponseEntity.ok("Memory saved.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    private void persistMemory(String prompt) throws Exception {
        float[] embedding = fetchEmbedding(prompt);

        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt", prompt);

        Map<String, Object> point = new HashMap<>();
        point.put("id", UUID.randomUUID().toString());
        point.put("payload", payload);
        point.put("vector", embedding);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("points", List.of(point));

        sendPut("https://vector.blackhatbadshah.com/collections/" + COLLECTION_NAME + "/points?wait=true", requestBody);
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

    // ======== FEN builders for UI (unused by main flow but kept) ========

    private String getCurrentTurn(UiPiece[][] board, Map<String, String> lastMove) {
        return (lastMove == null || lastMove.get("to") == null) ? "white" : "black";
    }

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
            if (empty > 0) fen.append(empty);
            if (row < 7) fen.append('/');
        }

        return fen + " " + ("white".equals(turn) ? "w" : "b") + " - - 0 1";
    }

    // ======== Feature Engineering + Policy ========

    private int pieceValue(Piece p) {
        return switch (p) {
            case WHITE_PAWN,  BLACK_PAWN  -> 1;
            case WHITE_KNIGHT,BLACK_KNIGHT,
                 WHITE_BISHOP,BLACK_BISHOP-> 3;
            case WHITE_ROOK,  BLACK_ROOK  -> 5;
            case WHITE_QUEEN, BLACK_QUEEN -> 9;
            case WHITE_KING,  BLACK_KING  -> 0;
            default -> 0;
        };
    }

    private double centralBonus(Square sq) {
        int file = sq.getFile().ordinal(); // 0..7
        int rank = sq.getRank().ordinal(); // 0..7
        double dx = Math.abs(file - 3.5);
        double dy = Math.abs(rank - 3.5);
        double dist = Math.sqrt(dx*dx + dy*dy);
        return 1.0 - Math.min(dist / 4.95, 1.0);
    }

    private boolean isCastle(Board board, Move m) {
        Piece mv = board.getPiece(m.getFrom());
        if (mv != Piece.WHITE_KING && mv != Piece.BLACK_KING) return false;
        int fromFile = m.getFrom().getFile().ordinal();
        int toFile   = m.getTo().getFile().ordinal();
        return Math.abs(toFile - fromFile) == 2;
    }

    /** Features: [captureVal, centerBonus, isPromo, isCastle, moverVal, pawnDoublePush] */
    private double[] featurizeMove(Board board, Move m) {
        Square from = m.getFrom();
        Square to   = m.getTo();

        Piece mover = board.getPiece(from);
        Piece target = board.getPiece(to); // if capture, piece is present (pre-move)
        int captureVal = (target == Piece.NONE) ? 0 : pieceValue(target);

        boolean promo = (m.getPromotion() != null);
        boolean castle = isCastle(board, m);

        int moverVal = pieceValue(mover);

        boolean pawn2 = (mover == Piece.WHITE_PAWN && from.getRank().getNotation().equals("2") && to.getRank().getNotation().equals("4")) ||
                        (mover == Piece.BLACK_PAWN && from.getRank().getNotation().equals("7") && to.getRank().getNotation().equals("5"));

        return new double[] {
            captureVal,
            centralBonus(to),
            promo ? 1.0 : 0.0,
            castle ? 1.0 : 0.0,
            moverVal,
            pawn2 ? 1.0 : 0.0
        };
    }

    private double dot(double[] w, double[] x) {
        double s = 0.0;
        int n = Math.min(w.length, x.length);
        for (int i = 0; i < n; i++) s += w[i] * x[i];
        return s;
    }

    private final String CHESS_MODEL_COLLECTION = "ztacs_chess_policy";
    private final String CHESS_MODEL_ID = "policy_v1";

    /** Default weights matching featurizeMove length (6). */
    private double[] defaultWeights() {
        // Favor captures, slight centralization, promotions, small castle bonus, tiny preference for moving value and pawn double pushes
        return new double[] { 0.8, 0.2, 0.6, 0.2, 0.05, 0.05 };
    }

    private double[] loadChessPolicyWeights() {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("ids", List.of(CHESS_MODEL_ID));
            JsonNode res = sendPost("https://vector.blackhatbadshah.com/collections/" + CHESS_MODEL_COLLECTION + "/points/retrieve", body);

            JsonNode result = res.get("result");
            if (result != null && result.isArray() && result.size() > 0) {
                JsonNode payload = result.get(0).get("payload");
                if (payload != null && payload.has("weights")) {
                    JsonNode wNode = payload.get("weights");
                    double[] w = new double[wNode.size()];
                    for (int i = 0; i < w.length; i++) w[i] = wNode.get(i).asDouble();
                    return w;
                }
            }
        } catch (Exception ignore) {}
        return defaultWeights();
    }

    private void saveChessPolicyWeights(double[] w) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        List<Double> wD = new ArrayList<>(w.length);
        List<Float>  wF = new ArrayList<>(w.length);
        for (double v : w) { wD.add(v); wF.add((float)v); }
        payload.put("weights", wD);

        Map<String, Object> point = new HashMap<>();
        point.put("id", CHESS_MODEL_ID);
        point.put("payload", payload);
        point.put("vector", wF); // store same length (6) as vector

        Map<String, Object> req = new HashMap<>();
        req.put("points", List.of(point));

        sendPut("https://vector.blackhatbadshah.com/collections/" + CHESS_MODEL_COLLECTION + "/points?wait=true", req);
    }

    private void logMoveExampleToQdrant(Board board, Move m, double[] x, double score) throws Exception {
        String fen = board.getFen();
        Map<String, Object> payload = new HashMap<>();
        payload.put("fen", fen);
        payload.put("uci", m.toString());
        payload.put("features", toList(x));
        payload.put("score", score);
        payload.put("ts", System.currentTimeMillis());

        String coll = "ztacs_chess_training";
        Map<String, Object> point = new HashMap<>();
        point.put("id", UUID.randomUUID().toString());
        point.put("payload", payload);
        point.put("vector", toFloatList(x)); // vector len = 6 (same as features)

        Map<String, Object> req = new HashMap<>();
        req.put("points", List.of(point));

        sendPut("https://vector.blackhatbadshah.com/collections/" + coll + "/points?wait=true", req);
    }

    private List<Double> toList(double[] x) {
        List<Double> out = new ArrayList<>(x.length);
        for (double v : x) out.add(v);
        return out;
    }
    private List<Float> toFloatList(double[] x) {
        List<Float> out = new ArrayList<>(x.length);
        for (double v : x) out.add((float)v);
        return out;
    }

}
