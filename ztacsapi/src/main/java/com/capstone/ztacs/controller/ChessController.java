import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/")
public class ChessController {

    @PostMapping("/chessValidate")
    public ResponseEntity<String> checkChessState(@RequestBody String state) {
        ObjectMapper mapper = new ObjectMapper();
        int whiteKings = 0;
        int blackKings = 0;

        try {
            JsonNode root = mapper.readTree(state);

            if (!root.isArray()) {
                return ResponseEntity.badRequest().body("Invalid JSON format: expected an array.");
            }

            for (JsonNode node : root) {
                JsonNode pieceNode = node.get("piece");
                int row = node.get("row").asInt();
                int col = node.get("col").asInt();

                if (pieceNode == null || !pieceNode.has("type") || !pieceNode.has("color")) {
                    return ResponseEntity.badRequest().body("Missing piece data.");
                }

                String type = pieceNode.get("type").asText();
                String color = pieceNode.get("color").asText();

                if (!isValidPiece(type) || !isValidColor(color)) {
                    return ResponseEntity.badRequest().body("Invalid piece type or color.");
                }

                if (row < 0 || row > 7 || col < 0 || col > 7) {
                    return ResponseEntity.badRequest().body("Invalid board position.");
                }

                if ("king".equals(type)) {
                    if ("white".equals(color)) whiteKings++;
                    else if ("black".equals(color)) blackKings++;
                }
            }

            if (whiteKings != 1 || blackKings != 1) {
                return ResponseEntity.badRequest().body("Board must have exactly 1 white king and 1 black king.");
            }

            return ResponseEntity.ok("Board state is valid.");

        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid JSON format or server error: " + e.getMessage());
        }
    }

    private boolean isValidPiece(String type) {
        return switch (type) {
            case "pawn", "rook", "knight", "bishop", "queen", "king" -> true;
            default -> false;
        };
    }

    private boolean isValidColor(String color) {
        return "white".equals(color) || "black".equals(color);
    }
}
