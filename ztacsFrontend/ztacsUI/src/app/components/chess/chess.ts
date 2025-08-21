import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { HttpClient, HttpHeaders } from '@angular/common/http';

interface Piece {
  type: 'pawn' | 'rook' | 'knight' | 'bishop' | 'queen' | 'king';
  color: 'white' | 'black';
}

@Component({
  selector: 'app-chess',
  standalone: true,
  imports: [CommonModule, DragDropModule],
  templateUrl: './chess.html',
  styleUrls: ['./chess.css'],
})
export class Chess {
  board = signal<(Piece | null)[][]>([]);
  dragRow = -1;
  dragCol = -1;


constructor(private http: HttpClient) {
  this.initBoard();
}

 initBoard() {
  const emptyRow: (Piece | null)[] = Array(8).fill(null);
  const newBoard = Array.from({ length: 8 }, () => [...emptyRow]);

  const pieceOrder: Piece['type'][] = [
    'rook', 'knight', 'bishop', 'queen', 'king', 'bishop', 'knight', 'rook'
  ];

  // Black pieces (top)
  for (let i = 0; i < 8; i++) {
    newBoard[0][i] = { type: pieceOrder[i], color: 'black' }; // Back rank
    newBoard[1][i] = { type: 'pawn', color: 'black' };         // Pawns
  }

  // White pieces (bottom)
  for (let i = 0; i < 8; i++) {
    newBoard[6][i] = { type: 'pawn', color: 'white' };         // Pawns
    newBoard[7][i] = { type: pieceOrder[i], color: 'white' }; // Back rank
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

  onDragStart(row: number, col: number): void {
    this.dragRow = row;
    this.dragCol = col;
  }

  onDrop(row: number, col: number): void {
    const currentBoard = this.board();
    const piece = currentBoard[this.dragRow]?.[this.dragCol];
    if (!piece || (this.dragRow === row && this.dragCol === col)) return;

    currentBoard[row][col] = piece;
    currentBoard[this.dragRow][this.dragCol] = null;
    this.board.set(currentBoard);
  }
sendBoardState() {
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

 const token = localStorage.getItem('jwt_token');
    const headers = new HttpHeaders({
      'Authorization': `Bearer ${token ?? ''}`,
      'Content-Type': 'application/json'
    });

  this.http.post('https://api.blackhatbadshah.com/chessValidate', state, { headers })
    .subscribe(
      ( response: any) => {
        console.log('API Response:', response);
      },
      (error: any) => {
        console.error('API Error:', error);
      }
    );
}



}
