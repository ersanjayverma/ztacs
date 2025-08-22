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

        // 1) Generate legal moves as Move objects
        List<Move> legalMovesObj = MoveGenerator.generateLegalMoves(board);
        if (legalMovesObj == null || legalMovesObj.isEmpty()) {
            return bad("No legal moves available.");
        }

        // 2) Derive UCI view + set for fast contains()
        List<String> legalUci = legalMovesObj.stream()
                .map(Move::toString)     // e2e4, e7e8q, etc.
                .sorted()
                .collect(Collectors.toList());
        Set<String> legalSet = new HashSet<>(legalUci);

        String turn = fen.contains(" w ") ? "white" : "black";

        // 3) Build prompt (MAKE THIS NON-STATIC if it calls instance methods like fetchEmbedding/searchQdrant)
        String prompt = buildPrompt(turn, fen, lastMove, legalUci);

        // 4) Get model move and normalize
        String raw = callAI(prompt);
        String aiMove = normalizeMove(raw);   // your normalizer should trim+lower

        // 5) If promotion omitted but required, default to queen
        if (aiMove != null && aiMove.length() == 4 && requiresPromotion(aiMove, board)) {
            aiMove = aiMove + "q";
        }

        // 6) Validate -> if invalid fallback; if valid, score only that single Move
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

/** Validate AI UCI; if invalid, deterministic fallback; if valid, run pickMove on just that one Move. */
private String chooseMoveUci(
        String aiMoveUci,
        Set<String> legalSet,
        List<Move> legalMovesObj,
        Board board) {

    String validUci = null;

    if (aiMoveUci != null && !aiMoveUci.isBlank()) {
        if (legalSet.contains(aiMoveUci)) {
            validUci = aiMoveUci;
        } else {
            // Try safe alt: add 'q' to 4-char or trim to 4 from >=5
            String alt = null;
            int len = aiMoveUci.length();
            if (len == 4) {
                alt = aiMoveUci + "q";
            } else if (len >= 5) {
                alt = aiMoveUci.substring(0, 4);
            }
            if (alt != null && legalSet.contains(alt)) {
                validUci = alt;
            }
        }
    }

    if (validUci == null) {
        // Fallback path — IMPORTANT: pass Move objects, not UCI strings
        return pickFallbackMove(board, legalMovesObj);
    }

    // Map UCI → Move object from the generated legal list
    Move candidate = findMoveByUci(validUci, legalMovesObj);
    if (candidate == null) {
        return pickFallbackMove(board, legalMovesObj);
    }

    // Score only this one candidate — pass a Collection<Move>
    String chosenUci = pickMove(board, Collections.singletonList(candidate));

    // Final guard
    if (chosenUci == null || !legalSet.contains(chosenUci)) {
        return pickFallbackMove(board, legalMovesObj);
    }
    return chosenUci;
}

/** Find the exact legal Move matching a given UCI string. */
private Move findMoveByUci(String uci, List<Move> legalMovesObj) {
    for (Move m : legalMovesObj) {
        if (uci.equals(m.toString())) return m;
    }
    return null;
}

private String pickMove(Board board, List<Move> candidates) {
    if (candidates == null || candidates.isEmpty()) return null;
    if (candidates.size() == 1) return candidates.get(0).toString();

    // Simple heuristic:
    // 1) Prefer promotions
    // 2) Prefer captures
    // 3) Otherwise first in list (stable)
    Move best = null;
    for (Move m : candidates) {
        if (best == null) { best = m; continue; }
        boolean mPromo = isPromotion(m);
        boolean bPromo = isPromotion(best);
        if (mPromo && !bPromo) { best = m; continue; }
        if (mPromo == bPromo) {
            boolean mCap = isCapture(board, m);
            boolean bCap = isCapture(board, best);
            if (mCap && !bCap) { best = m; }
        }
    }
    return best.toString();
}

/** Deterministic fallback from the full legal list. */
private String pickFallbackMove(Board board, List<Move> legalMoves) {
    if (legalMoves == null || legalMoves.isEmpty()) return null;

    // Order: promotions > captures > checks > quiet (stable within each class)
    Move promo = null, capture = null, check = null, quiet = null;

    for (Move m : legalMoves) {
        if (isPromotion(m)) { if (promo == null) promo = m; continue; }
        if (isCapture(board, m)) { if (capture == null) capture = m; continue; }
        if (givesCheck(board, m)) { if (check == null) check = m; continue; }
        if (quiet == null) quiet = m;
    }

    Move chosen = promo != null ? promo
                : capture != null ? capture
                : check != null ? check
                : quiet;

    return chosen != null ? chosen.toString() : null;
}

/** Lightweight helpers used above. Adjust if your engine exposes richer flags. */
private boolean isPromotion(Move m) {
    // chesslib encodes promotions in SAN/flags; in UCI string it ends with the piece (e.g., e7e8q)
    String uci = m.toString();
    return uci.length() == 5;
}

private boolean isCapture(Board board, Move m) {
    // Apply move on a copy to inspect capture via board state if needed.
    // For speed, infer via destination square occupancy before move:
    // chesslib API: board.getPiece(Square) returns Piece
    try {
        return board.getPiece(m.getTo()) != com.github.bhlangonijr.chesslib.Piece.NONE;
    } catch (Exception e) {
        return false;
    }
}

private boolean givesCheck(Board board, Move m) {
    // Make a copy and test check status after move.
    try {
        Board copy = new Board();
        copy.loadFromFen(board.getFen());
        copy.doMove(m);
        return copy.isKingAttacked();
    } catch (Exception e) {
        return false;
    }
}

 private  String buildPrompt(
        String turn,
        String fen,
        Map<String, String> lastMove,
        List<String> legalUci) throws Exception {

    String last = (lastMove != null && lastMove.get("from") != null && lastMove.get("to") != null)
        ? (lastMove.get("from") + lastMove.get("to"))
        : "none";

    // 1. Build a text for embedding (FEN + context works best)
    String contextText = String.format("Turn: %s | FEN: %s | Last: %s", turn, fen, last);

    // 2. Fetch embedding
    float[] embedding = fetchEmbedding(contextText);

    // 3. Get similar prompts from Qdrant
    List<String> similarPrompts = searchQdrant(embedding);

    // 4. Merge retrieved context into the final system prompt
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


    /** Trim, lowercase, and keep only [a-h][1-8][a-h][1-8][qrbn]? */
    private  String normalizeMove(String raw) {
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
    private  boolean requiresPromotion(String uci4, Board board) {
        if (uci4 == null || uci4.length() != 4) return false;
        Square from = Square.fromValue(uci4.substring(0, 2).toUpperCase(Locale.ROOT));
        Square to   = Square.fromValue(uci4.substring(2, 4).toUpperCase(Locale.ROOT));
        Piece p = board.getPiece(from);
        if (p == Piece.WHITE_PAWN && to.getRank().getNotation().equals("8")) return true;
        if (p == Piece.BLACK_PAWN && to.getRank().getNotation().equals("1")) return true;
        return false;
    }

/** ML-powered fallback: score legal moves with a tiny linear policy and persist to Qdrant. */
private String pickFallbackMove(Board board, Collection<Move> legal) {
    if (legal == null || legal.isEmpty()) return "0000";

    // 1) Load / init weights (length must match feature vector length)
    double[] w = loadChessPolicyWeights(); // [w0..wN]

    // 2) Score all moves and pick argmax
    String bestUci = null;
    double bestScore = Double.NEGATIVE_INFINITY;

    for (Move m : legal) {
        double[] x = featurizeMove(board, m);      // features
        double s = dot(w, x);                      // score
        if (s > bestScore) { bestScore = s; bestUci = m.toString(); }

        // Optional: log each candidate to Qdrant for offline analysis
        try { logMoveExampleToQdrant(board, m, x, s); } catch (Exception ignore) {}
    }

    // 3) (Optional online tweak) tiny nudged update toward chosen argmax
    //    This is a bandit-style, naive ascent (no regrets): w <- w + lr * x_best
    //    Keep it *very* small to avoid divergence.
    if (bestUci != null) {
        double[] xbest = featurizeMove(board, findMoveByUci(legal, bestUci));
        double lr = 0.001;
        for (int i = 0; i < w.length; i++) w[i] += lr * xbest[i];
        try { saveChessPolicyWeights(w); } catch (Exception ignore) {}
        return bestUci;
    }

    // Fallback to first if scoring failed
    Iterator<Move> it = legal.iterator();
    return it.hasNext() ? it.next().toString() : "0000";
}

    private  ResponseEntity<Map<String, Object>> bad(String msg) {
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

// ---------- ML policy: features & scoring ----------

/** Map chess piece to a simple material value. */
private  int pieceValue(com.github.bhlangonijr.chesslib.Piece p) {
    return switch (p) {
        case WHITE_PAWN,  BLACK_PAWN  -> 1;
        case WHITE_KNIGHT,BLACK_KNIGHT,
             WHITE_BISHOP,BLACK_BISHOP-> 3;
        case WHITE_ROOK,  BLACK_ROOK  -> 5;
        case WHITE_QUEEN, BLACK_QUEEN -> 9;
        case WHITE_KING,  BLACK_KING  -> 0; // avoid trading on king
        default -> 0;
    };
}

/** Centralization bonus for destination square [0..1], center = higher. */
private  double centralBonus(Square sq) {
    // files a..h => 0..7, ranks 1..8 => 0..7
    int file = sq.getFile().ordinal(); // 0..7
    int rank = sq.getRank().ordinal(); // 0..7
    // distance to center (3.5, 3.5)
    double dx = Math.abs(file - 3.5);
    double dy = Math.abs(rank - 3.5);
    double dist = Math.sqrt(dx*dx + dy*dy);   // 0..~4.95
    // invert and normalize
    return 1.0 - Math.min(dist / 4.95, 1.0);
}

/** Is castle by UCI-like displacement (king moves two files). */
private  boolean isCastle(Board board, Move m) {
    Piece mv = board.getPiece(m.getFrom());
    if (mv != Piece.WHITE_KING && mv != Piece.BLACK_KING) return false;
    int fromFile = m.getFrom().getFile().ordinal();
    int toFile   = m.getTo().getFile().ordinal();
    return Math.abs(toFile - fromFile) == 2;
}

/** Feature vector for a move (keep in sync with default weights). */
private  double[] featurizeMove(Board board, Move m) {
    // Features:
    // [0] capture_value
    // [1] centralization (dest)
    // [2] is_promotion (0/1)
    // [3] is_castle (0/1)
    // [4] mover_piece_value
    // [5] pawn_push_two (0/1)
    Square from = m.getFrom();
    Square to   = m.getTo();

    Piece mover = board.getPiece(from);
    Piece target = board.getPiece(to); // if capture, piece is present (pre-move)
    int captureVal = (target == Piece.NONE) ? 0 : pieceValue(target);

    boolean promo = (m.getPromotion() != null);
    boolean castle = isCastle(board, m);

    int moverVal = pieceValue(mover);

    // pawn double push: from rank 2->4 (white) or 7->5 (black)
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

private  double dot(double[] w, double[] x) {
    double s = 0.0;
    int n = Math.min(w.length, x.length);
    for (int i = 0; i < n; i++) s += w[i] * x[i];
    return s;
}

private  Move findMoveByUci(Collection<Move> legal, String uci) {
    for (Move m : legal) if (m.toString().equals(uci)) return m;
    throw new IllegalArgumentException("Move not in legal set: " + uci);
}

// ---------- Model persistence in Qdrant ("quaddb") ----------

private  final String CHESS_MODEL_COLLECTION = "ztacs_chess_policy";
private  final String CHESS_MODEL_ID = "policy_v1";

/** Default weights matching featurizeMove length (6). */
private  double[] defaultWeights() {
    // Favor captures, slight centralization, value movers, small castle bonus
    return new double[] { 0.8, 0.2, 0.6, 0.2, 0.05, 0.05 };
}

private double[] loadChessPolicyWeights() {
    try {
        // Qdrant retrieve by id
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
    // Store as doubles; Qdrant vector expects float; we'll store both
    List<Double> wD = new ArrayList<>(w.length);
    List<Float>  wF = new ArrayList<>(w.length);
    for (double v : w) { wD.add(v); wF.add((float)v); }
    payload.put("weights", wD);

    Map<String, Object> point = new HashMap<>();
    point.put("id", CHESS_MODEL_ID);
    point.put("payload", payload);
    point.put("vector", wF); // not used for search here, but valid

    Map<String, Object> req = new HashMap<>();
    req.put("points", List.of(point));

    sendPut("https://vector.blackhatbadshah.com/collections/" + CHESS_MODEL_COLLECTION + "/points?wait=true", req);
}

/** Log scored candidate move for offline training/analytics. */
private void logMoveExampleToQdrant(Board board, Move m, double[] x, double score) throws Exception {
    String fen = board.getFen();
    Map<String, Object> payload = new HashMap<>();
    payload.put("fen", fen);
    payload.put("uci", m.toString());
    payload.put("features", toList(x));
    payload.put("score", score);
    payload.put("ts", System.currentTimeMillis());

    // Save into a separate collection
    String coll = "ztacs_chess_training";
    Map<String, Object> point = new HashMap<>();
    point.put("id", UUID.randomUUID().toString());
    point.put("payload", payload);
    // Optional vector: reuse features (as float) so you can vector-search similar situations
    point.put("vector", toFloatList(x));

    Map<String, Object> req = new HashMap<>();
    req.put("points", List.of(point));

    sendPut("https://vector.blackhatbadshah.com/collections/" + coll + "/points?wait=true", req);
}

private  List<Double> toList(double[] x) {
    List<Double> out = new ArrayList<>(x.length);
    for (double v : x) out.add(v);
    return out;
}
private  List<Float> toFloatList(double[] x) {
    List<Float> out = new ArrayList<>(x.length);
    for (double v : x) out.add((float)v);
    return out;
}


}
