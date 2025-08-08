import { Component, ViewChild, ElementRef, AfterViewInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpClientModule, HttpHeaders } from '@angular/common/http';

  type Message = {
  sender: 'user' | 'bot';
  text: string;
};
@Component({
  selector: 'app-home',
  standalone: true,
  templateUrl: './home.html',
  styleUrls: ['./home.css'],
  imports: [CommonModule, FormsModule, HttpClientModule]
})
export class Home implements AfterViewInit {
  userInput: string = '';


  messages = signal<Message[]>([]);

  @ViewChild('chatMessages') chatMessages!: ElementRef<HTMLDivElement>;

  constructor(private http: HttpClient) {}

  ngAfterViewInit() {
    this.scrollToBottom();
  }

  sendMessage() {
    const prompt = this.userInput.trim();
    if (!prompt) return;

        this.messages.update(msgs => [...msgs, { sender: 'user', text: prompt }]);
    this.userInput = '';

    const token = localStorage.getItem('jwt_token');
    const headers = new HttpHeaders({
      'Authorization': `Bearer ${token ?? ''}`,
      'Content-Type': 'application/json'
    });

    this.http.post<{ response: string }>(
      'https://api.blackhatbadshah.com/api/ai/ask',
      prompt, // just send prompt as string
      {
        headers: headers,
        responseType: 'json'
      }
    ).subscribe({
      next: (res) => {
          this.messages.update(msgs => [...msgs, { sender: 'bot', text: res.response }]);
        setTimeout(() => this.scrollToBottom(), 300);
      },
      error: () => {
        this.messages.update(msgs=>[...msgs,{ sender: 'bot', text: 'Error communicating with server.' }]);
        setTimeout(() => this.scrollToBottom(), 100);
      }
    });

  }

  scrollToBottom() {
    if (this.chatMessages && this.chatMessages.nativeElement) {
      const container = this.chatMessages.nativeElement;
      container.scrollTop = container.scrollHeight;
    }
  }
}
