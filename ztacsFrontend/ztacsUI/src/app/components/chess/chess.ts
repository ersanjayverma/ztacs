import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { HttpClient, HttpClientModule, HttpHeaders } from '@angular/common/http';
import { Chess, PieceSymbol, Color, Square, Move } from 'chess.js';

interface Piece { type: 'pawn'|'rook'|'knight'|'bishop'|'queen'|'king'; color: 'white'|'black'; }

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

  dragRow = -1;
  dragCol = -1;
  lastMove: { from: string; to: string } | null = null;

  isLoading = signal(false);
  status = signal<string | null>(null);

  constructor(private http: HttpClient) {
    this.syncFromGame();
  }


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
    const files = 'abcdefgh';
    return (files[col] + (8 - row)) as Square;
  }

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
    this.syncFromGame();


    if (!this.game.isGameOver() && !this.game.isStalemate() && !this.game.isDraw()) {
      this.makeCpuMove();
    }
  }


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
        if (!/^[a-h][1-8][a-h][1-8]([qrbn])?$/.test(aiMove)) {
          console.warn('Invalid AI move format:', aiMove);
          this.isLoading.set(false);
          return;
        }
        const from = aiMove.slice(0,2) as Square;
        const to = aiMove.slice(2,4) as Square;
        const promo = (aiMove.length === 5 ? aiMove[4] : 'q') as PieceSymbol;

        const mv = this.game.move({ from, to, promotion: promo });
        if (!mv) { console.warn('AI suggested illegal move', aiMove); this.isLoading.set(false); return; }

        this.lastMove = { from, to };
        this.syncFromGame();
      },
      error: err => console.error('API Error:', err),
      complete: () => this.isLoading.set(false),
    });
  }

  private updateStatusFromGame() {
    if (this.game.isCheckmate()) {
      const loser = this.game.turn() === 'w' ? 'White' : 'Black';
      const winner = loser === 'White' ? 'Black' : 'White';
      this.status.set(`Checkmate. ${winner} wins.`);
      return;
    }
    if (this.game.isStalemate()) { this.status.set('Stalemate. Draw.'); return; }
    if (this.game.isDraw()) { this.status.set('Draw.'); return; }
    if (this.game.isCheck()) {
      const side = this.game.turn() === 'w' ? 'White' : 'Black';
      this.status.set(`Check on ${side}.`); return;
    }
    this.status.set(null);
  }
}
