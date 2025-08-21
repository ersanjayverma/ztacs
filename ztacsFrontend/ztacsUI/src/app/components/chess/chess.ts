import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { Chess, PieceSymbol, Color, Square } from 'chess.js';

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
      pawn: 'p',
      rook: 'r',
      knight: 'n',
      bishop: 'b',
      queen: 'q',
      king: 'k'
    };
    return map[type];
  }

  mapColorToSymbol(color: Piece['color']): Color {
    return color === 'white' ? 'w' : 'b';
  }

  onDragStart(row: number, col: number): void {
    const piece = this.board()[row][col];
    if (!piece || piece.color !== this.currentTurn()) {
      return; // Prevent dragging if it's not this player's turn
    }
    this.dragRow = row;
    this.dragCol = col;
  }

  buildFEN(board: (Piece | null)[][], turn: 'white' | 'black'): string {
    const pieceToFenChar = (p: Piece) => {
      const map: Record<Piece['type'], string> = {
        pawn: 'p',
        rook: 'r',
        knight: 'n',
        bishop: 'b',
        queen: 'q',
        king: 'k',
      };
      let char = map[p.type];
      return p.color === 'white' ? char.toUpperCase() : char;
    };

    let fenRows: string[] = [];

    for (let r = 0; r < 8; r++) {
      let fenRow = '';
      let emptyCount = 0;

      for (let c = 0; c < 8; c++) {
        const piece = board[r][c];
        if (!piece) {
          emptyCount++;
        } else {
          if (emptyCount > 0) {
            fenRow += emptyCount;
            emptyCount = 0;
          }
          fenRow += pieceToFenChar(piece);
        }
      }

      if (emptyCount > 0) fenRow += emptyCount;
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

    const chess = new Chess();

    // Build and load FEN with current board and current turn
    const fen = this.buildFEN(currentBoard, this.currentTurn());
    chess.load(fen);

    const from = this.convertToSquare(this.dragRow, this.dragCol);
    const to = this.convertToSquare(row, col);

    // Attempt move with promotion always queen for simplicity
    const move = chess.move({ from, to, promotion: 'q' });

    if (!move) {
      console.warn('Illegal move:', from, '→', to);
      return;
    }

    // Update board with the move
    currentBoard[row][col] = piece;
    currentBoard[this.dragRow][this.dragCol] = null;
    this.board.set(currentBoard);

    this.lastMove = { from, to };

    // Switch turn after valid move
    this.currentTurn.set(this.currentTurn() === 'white' ? 'black' : 'white');
     this.makeCpuMove();
  }

 
  
  makeCpuMove() {
  const board = this.board();
  const state: { piece: Piece; row: number; col: number }[] = [];

  for (let row = 0; row < 8; row++) {
    for (let col = 0; col < 8; col++) {
      const piece = board[row][col];
      if (piece) {
        state.push({ piece, row, col });
      }
    }
  }

  this.http.post('https://api.blackhatbadshah.com/api/ai/chessNextMove', {
    pieces: state,
    lastMove: this.lastMove
  }).subscribe({
    next: (response: any) => {
      const move: { from: string; to: string } = {
        from: response?.aiMove?.slice(0, 2),
        to: response?.aiMove?.slice(2, 4)
      };

      if (!move.from || !move.to) {
        console.warn('Invalid AI move:', response);
        return;
      }

      const fromCoords = this.squareToCoords(move.from);
      const toCoords = this.squareToCoords(move.to);

      const currentBoard = this.board().map(r => [...r]);
      const movingPiece = currentBoard[fromCoords.row]?.[fromCoords.col];

      if (!movingPiece) {
        console.warn('No piece found at', move.from);
        return;
      }

      // Apply the move
      currentBoard[toCoords.row][toCoords.col] = movingPiece;
      currentBoard[fromCoords.row][fromCoords.col] = null;

      this.board.set(currentBoard);
      this.lastMove = move;

      // Switch turn back to player
      this.currentTurn.set(this.currentTurn() === 'white' ? 'black' : 'white');
    },
    error: (error: any) => {
      console.error('API Error:', error);
    }
  });

  }
squareToCoords(square: string): { row: number; col: number } {
  const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
  const col = files.indexOf(square[0]);
  const row = 8 - parseInt(square[1], 10);
  return { row, col };
}

}
