import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { HttpClient, HttpClientModule, HttpHeaders } from '@angular/common/http';
import { Chess, PieceSymbol, Color } from 'chess.js';

interface Piece {
  type: 'pawn' | 'rook' | 'knight' | 'bishop' | 'queen' | 'king';
  color: 'white' | 'black';
}

@Component({
  selector: 'app-chess',
  standalone: true,
  imports: [CommonModule, DragDropModule, HttpClientModule],
  templateUrl: './chess.html',
  styleUrls: ['./chess.css'],
})
export class ChessComponent {
  board = signal<(Piece | null)[][]>([]);
  dragRow = -1;
  dragCol = -1;
  lastMove: { from: string; to: string } | null = null;

  currentTurn = signal<'white' | 'black'>('white');

  // NEW: UI state
  isLoading = signal(false);
  status = signal<string | null>(null); // "Check on Black", "Checkmate. White wins.", etc.

  constructor(private http: HttpClient) {
    this.initBoard();
  }

  initBoard() {
    const emptyRow: (Piece | null)[] = Array(8).fill(null);
    const newBoard = Array.from({ length: 8 }, () => [...emptyRow]);

    const pieceOrder: Piece['type'][] = [
      'rook', 'knight', 'bishop', 'queen', 'king', 'bishop', 'knight', 'rook'
    ];

    // Black pieces
    for (let i = 0; i < 8; i++) {
      newBoard[0][i] = { type: pieceOrder[i], color: 'black' };
      newBoard[1][i] = { type: 'pawn', color: 'black' };
    }

    // White pieces
    for (let i = 0; i < 8; i++) {
      newBoard[6][i] = { type: 'pawn', color: 'white' };
      newBoard[7][i] = { type: pieceOrder[i], color: 'white' };
    }

    this.board.set(newBoard);
    this.updateStatusFromFEN(this.buildFEN(newBoard, this.currentTurn()));
  }

  getPieceSymbol(type: Piece['type'], color: Piece['color']): string {
    const symbols: Record<Piece['type'], { white: string; black: string }> = {
      pawn:   { white: '♙', black: '♟' },
      rook:   { white: '♖', black: '♜' },
      knight: { white: '♘', black: '♞' },
      bishop: { white: '♗', black: '♝' },
      queen:  { white: '♕', black: '♛' },
      king:   { white: '♔', black: '♚' },
    };
    return symbols[type][color];
  }

  isWhiteSquare(row: number, col: number): boolean {
    return (row + col) % 2 === 0;
  }

  convertToSquare(row: number, col: number): string {
    const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
    return files[col] + (8 - row);
  }

  mapTypeToPieceSymbol(type: Piece['type']): PieceSymbol {
    const map: Record<Piece['type'], PieceSymbol> = {
      pawn: 'p', rook: 'r', knight: 'n', bishop: 'b', queen: 'q', king: 'k'
    };
    return map[type];
  }

  mapColorToSymbol(color: Piece['color']): Color {
    return color === 'white' ? 'w' : 'b';
  }

  onDragStart(row: number, col: number): void {
    const piece = this.board()[row][col];
    if (!piece || piece.color !== this.currentTurn()) return;
    this.dragRow = row;
    this.dragCol = col;
  }

buildFEN(board: (Piece | null)[][], turn: 'white' | 'black' = this.currentTurn()): string {
  const pieceToFenChar = (p: Piece) => {
    const map: Record<Piece['type'], string> = {
      pawn: 'p', rook: 'r', knight: 'n', bishop: 'b', queen: 'q', king: 'k',
    };
    const char = map[p.type];
    return p.color === 'white' ? char.toUpperCase() : char;
  };

    const fenRows: string[] = [];
    for (let r = 0; r < 8; r++) {
      let fenRow = '';
      let empty = 0;
      for (let c = 0; c < 8; c++) {
        const piece = board[r][c];
        if (!piece) { empty++; continue; }
        if (empty > 0) { fenRow += empty; empty = 0; }
        fenRow += pieceToFenChar(piece);
      }
      if (empty > 0) fenRow += empty;
      fenRows.push(fenRow);
    }

    const fenBoard = fenRows.join('/');
    const castling = '-';
    const enPassant = '-';
    const halfmove = '0';
    const fullmove = '1';

    return `${fenBoard} ${turn === 'white' ? 'w' : 'b'} ${castling} ${enPassant} ${halfmove} ${fullmove}`;
  }

  onDrop(row: number, col: number): void {
    const currentBoard = this.board().map(r => [...r]);
    const piece = currentBoard[this.dragRow]?.[this.dragCol];
    if (!piece || (this.dragRow === row && this.dragCol === col)) return;

    const fenBefore = this.buildFEN(currentBoard, this.currentTurn());
    const chess = new Chess(fenBefore);

    const from = this.convertToSquare(this.dragRow, this.dragCol);
    const to = this.convertToSquare(row, col);

    // Try move (promotion default queen for simplicity)
    const move = chess.move({ from, to, promotion: 'q' });
    if (!move) {
      console.warn('Illegal move:', from, '→', to);
      return;
    }

    // Apply on UI board
    currentBoard[row][col] = piece;
    currentBoard[this.dragRow][this.dragCol] = null;
    this.board.set(currentBoard);
    this.lastMove = { from, to };

    // Switch turn after valid move
    this.currentTurn.set(this.currentTurn() === 'white' ? 'black' : 'white');

    // Update status after player's move
    const fenAfter = this.buildFEN(this.board(), this.currentTurn());
    this.updateStatusFromFEN(fenAfter);

    // If game not ended, ask CPU
    const g = new Chess(fenAfter);
    if (!g.isCheckmate() && !g.isStalemate() && !g.isDraw()) {
      this.makeCpuMove();
    }
  }

  makeCpuMove() {
    const fen = this.buildFEN(this.board(), this.currentTurn());
    const token = localStorage.getItem('jwt_token');
    const headers = new HttpHeaders({
      'Authorization': `Bearer ${token ?? ''}`,
      'Content-Type': 'application/json'
    });

    // Loader ON
    this.isLoading.set(true);

    this.http.post(
      'https://api.blackhatbadshah.com/api/ai/chessNextMove',
      { validMoves: new Chess(fen).moves(), fen, lastMove: this.lastMove },
      { headers }
    ).subscribe({
      next: (response: any) => {
        const aiMove = (response?.aiMove || '').trim().toLowerCase();
        if (!/^[a-h][1-8][a-h][1-8]([qrbn])?$/.test(aiMove)) {
          console.warn('Invalid AI move format:', aiMove);
          return;
        }

        const from = aiMove.slice(0, 2);
        const to = aiMove.slice(2, 4);
        const promotion = aiMove.length === 5 ? (aiMove[4] as PieceSymbol) : undefined;

        const chess = new Chess(fen);
        const legalMove = chess.move({ from, to, promotion: promotion ?? 'q' });
        if (!legalMove) {
          console.warn('AI suggested illegal move:', { from, to, promotion });
          return;
        }

        const fromCoords = this.squareToCoords(from);
        const toCoords = this.squareToCoords(to);

        const currentBoard = this.board().map(r => [...r]);
        const movingPiece = currentBoard[fromCoords.row]?.[fromCoords.col];
        if (!movingPiece) {
          console.warn('No piece at', from, fromCoords);
          return;
        }

        // Apply the move
        currentBoard[toCoords.row][toCoords.col] = movingPiece;
        currentBoard[fromCoords.row][fromCoords.col] = null;
        this.board.set(currentBoard);
        this.lastMove = { from, to };

        // Switch turn back to player
        this.currentTurn.set(this.currentTurn() === 'white' ? 'black' : 'white');

        // Update status after CPU move
        const newFen = this.buildFEN(this.board(), this.currentTurn());
        this.updateStatusFromFEN(newFen);
      },
      error: (error: any) => {
        console.error('API Error:', error);
      },
      complete: () => {
        // Loader OFF
        this.isLoading.set(false);
      }
    });
  }

  squareToCoords(square: string): { row: number; col: number } {
    const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
    const col = files.indexOf(square[0]);
    const row = 8 - parseInt(square[1], 10);
    return { row, col };
  }

  // NEW: Status evaluator using chess.js
  private updateStatusFromFEN(fen: string) {
  const game = new Chess(fen);

  if (game.isCheckmate()) {
    const loser = game.turn() === 'w' ? 'White' : 'Black'; // side to move is checkmated
    const winner = loser === 'White' ? 'Black' : 'White';
    this.status.set(`Checkmate. ${winner} wins.`);
    return;
  }

  if (game.isStalemate()) {
    this.status.set('Stalemate. Draw.');
    return;
  }

  if (game.isDraw()) {
    this.status.set('Draw.');
    return;
  }

  if (game.isCheck()) {
    const side = game.turn() === 'w' ? 'White' : 'Black'; // side to move is in check
    this.status.set(`Check on ${side}.`);
    return;
  }

  this.status.set(null);
}
}
