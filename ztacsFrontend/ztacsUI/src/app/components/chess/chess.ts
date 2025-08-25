import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { HttpClient, HttpClientModule, HttpHeaders } from '@angular/common/http';
import { Chess, PieceSymbol, Color, Square, Move } from 'chess.js';

interface Piece { type: 'pawn'|'rook'|'knight'|'bishop'|'queen'|'king'; color: 'white'|'black'; }
interface ScoredMove {
  ply: number;            // 1-based move index in half-moves
  side: 'White'|'Black';  // who moved
  san: string;            // SAN (e.g., Nf3, O-O)
  uci: string;            // e2e4, g1f3, e7e8q
  evalCp: number;         // evaluation in centipawns (+ = White better)
}

@Component({
  selector: 'app-chess',
  standalone: true,
  imports: [CommonModule, DragDropModule, HttpClientModule],
  templateUrl: './chess.html',
  styleUrls: ['./chess.css'],
})
export class ChessComponent {
  private game = new Chess(); 
  board = signal<(Piece | null)[][]>([]);
  currentTurn = signal<'white'|'black'>('white');

  // Score panel
  score = {
    relevance: 0, clarity: 0, completeness: 0, overall: 0
  };
  movesLog = signal<ScoredMove[]>([]);
  lastEvalCp = signal<number>(0); // latest eval after the last move

  dragRow = -1;
  dragCol = -1;
  lastMove: { from: string; to: string } | null = null;

  isLoading = signal(false);
  status = signal<string | null>(null);

  constructor(private http: HttpClient) { this.syncFromGame(); }

  // ---------- Evaluation ----------
private evaluateGameCp(g: Chess): number {
  // Mate/draw shortcuts
  if (g.isCheckmate()) return (g.turn() === 'w') ? -99999 : 99999;
  if (g.isStalemate() || g.isDraw()) return 0;

  // Material
  const val: Record<PieceSymbol, number> = { p:100, n:320, b:330, r:500, q:900, k:0 };
  let cp = 0;
  for (const row of g.board()) {
    for (const sq of row) {
      if (!sq) continue;
      cp += (sq.color === 'w' ? 1 : -1) * val[sq.type];
    }
  }

  // Mobility for each side with turn forced in FEN
  const base = g.fen().split(' ');
  // base = [pieces, turn, castling, ep, half, full]
  const fenW = [base[0], 'w', base[2], base[3], base[4], base[5]].join(' ');
  const fenB = [base[0], 'b', base[2], base[3], base[4], base[5]].join(' ');
  const wMoves = new Chess(fenW).moves().length;
  const bMoves = new Chess(fenB).moves().length;

  const mobility = (wMoves - bMoves) * 2; // light weight
  return cp + mobility;
}


  private recordMove(mv: Move) {
    const ply = this.game.history().length; // after move
    const side: 'White'|'Black' = (mv.color === 'w') ? 'White' : 'Black';
    const uci = mv.from + mv.to + (mv.promotion ? mv.promotion : '');
    const evalCp = this.evaluateGameCp(this.game);
    this.lastEvalCp.set(evalCp);

    const row: ScoredMove = { ply, side, san: mv.san, uci, evalCp };
    this.movesLog.update(list => [...list, row]);
  }

  // ---------- Sync / UI ----------
  private syncFromGame() {
    const gBoard = this.game.board();
    const mapped: (Piece|null)[][] = gBoard.map(row => row.map(sq => {
      if (!sq) return null;
      const color: Piece['color'] = sq.color === 'w' ? 'white' : 'black';
      const typeMap: Record<PieceSymbol, Piece['type']> = { p:'pawn', r:'rook', n:'knight', b:'bishop', q:'queen', k:'king' };
      return { color, type: typeMap[sq.type] };
    }));
    this.board.set(mapped);
    this.currentTurn.set(this.game.turn() === 'w' ? 'white' : 'black');
    this.updateStatusFromGame();

    // Update banner score even if no moves yet
    this.lastEvalCp.set(this.evaluateGameCp(this.game));
  }

  getPieceSymbol(type: Piece['type'], color: Piece['color']): string {
    const symbols: Record<Piece['type'], { white: string; black: string }> = {
      pawn:{white:'♙',black:'♟'}, rook:{white:'♖',black:'♜'}, knight:{white:'♘',black:'♞'},
      bishop:{white:'♗',black:'♝'}, queen:{white:'♕',black:'♛'}, king:{white:'♔',black:'♚'},
    };
    return symbols[type][color];
  }
  isWhiteSquare(row: number, col: number) { return (row + col) % 2 === 0; }
  private convertToSquare(row: number, col: number): Square {
    const files = 'abcdefgh'; return (files[col] + (8 - row)) as Square;
  }

  // ---------- DnD ----------
  onDragStart(row: number, col: number) {
    const piece = this.board()[row][col];
    if (!piece) return;
    if ((this.game.turn() === 'w' && piece.color !== 'white') ||
        (this.game.turn() === 'b' && piece.color !== 'black')) return;
    this.dragRow = row; this.dragCol = col;
  }

  onDrop(row: number, col: number) {
    const from = this.convertToSquare(this.dragRow, this.dragCol);
    const to   = this.convertToSquare(row, col);

    const mv = this.game.move({ from, to, promotion: 'q' });
    if (!mv) return;

    this.lastMove = { from, to };
    this.recordMove(mv);        // <<=== add to log with eval
    this.syncFromGame();

    if (!this.game.isGameOver() && !this.game.isStalemate() && !this.game.isDraw()) {
      this.makeCpuMove();
    }
  }
  trackByPly(index: number, m: { ply: number }) { return m.ply; }
  // ---------- CPU ----------
  makeCpuMove() {
    const token = localStorage.getItem('jwt_token') ?? '';
    const headers = new HttpHeaders({ 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' });

    this.isLoading.set(true);

    const fen = this.game.fen();
    const validMoves = this.game.moves();
    this.http.post(
      'https://api.blackhatbadshah.com/api/ai/chessNextMove',
      { validMoves, fen, lastMove: this.lastMove },
      { headers }
    ).subscribe({
      next: (response: any) => {
        const aiMove = String(response?.aiMove ?? '').trim().toLowerCase();
        if (!/^[a-h][1-8][a-h][1-8]([qrbn])?$/.test(aiMove)) { this.isLoading.set(false); return; }

        const from = aiMove.slice(0,2) as Square;
        const to = aiMove.slice(2,4) as Square;
        const promo = (aiMove.length === 5 ? aiMove[4] : 'q') as PieceSymbol;
        const mv = this.game.move({ from, to, promotion: 'q' });
                if (!mv) { this.isLoading.set(false); return; }

        if (!mv) return;
        this.lastMove = { from, to };
        this.recordMove(mv);     // logs SAN/uci and sets lastEvalCp immediately
        this.syncFromGame();     // redraws board & banner
      },
      error: err => console.error('API Error:', err),
      complete: () => this.isLoading.set(false),
    });
  }

  // ---------- Status ----------
  private updateStatusFromGame() {
    if (this.game.isCheckmate()) {
      const loser = this.game.turn() === 'w' ? 'White' : 'Black';
      const winner = loser === 'White' ? 'Black' : 'White';
      this.status.set(`Checkmate. ${winner} wins.`); return;
    }
    if (this.game.isStalemate()) { this.status.set('Stalemate. Draw.'); return; }
    if (this.game.isDraw()) { this.status.set('Draw.'); return; }
    if (this.game.isCheck()) {
      const side = this.game.turn() === 'w' ? 'White' : 'Black';
      this.status.set(`Check on ${side}.`); return;
    }
    this.status.set(null);
  }

  // ---------- Formatting helpers for template ----------
  formatEval(evalCp: number): string {
    if (Math.abs(evalCp) >= 99999) return evalCp > 0 ? '+M' : '-M';
    return (evalCp / 100).toFixed(2);
  }
  advantageText(): string {
    const cp = this.lastEvalCp();
    if (Math.abs(cp) >= 99999) return cp > 0 ? 'White mates' : 'Black mates';
    const pawns = (cp/100).toFixed(2);
    if (cp > 50) return `White +${pawns}`;
    if (cp < -50) return `Black ${pawns}`;
    return `≈ Equal ${pawns}`;
  }
  
}
