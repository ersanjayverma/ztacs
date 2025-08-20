import { Component, ViewChild, ElementRef, AfterViewInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpClientModule, HttpHeaders } from '@angular/common/http';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';

type Message = {
  sender: 'user' | 'bot';
  text: SafeHtml; // store as safe HTML for rendering
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
  listening = false;
  recognition: any;
  isChatOpen: boolean = false;
  @ViewChild('chatMessages') chatMessages!: ElementRef<HTMLDivElement>;

  constructor(private http: HttpClient, private sanitizer: DomSanitizer) {
    // Initialize Speech Recognition
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (SpeechRecognition) {
      this.recognition = new SpeechRecognition();
      this.recognition.lang = 'en-US';
      this.recognition.continuous = false;
      this.recognition.interimResults = false;

      this.recognition.onresult = (event: any) => {
        const transcript = event.results[0][0].transcript;
        this.userInput = transcript;
        this.sendMessage();
      };

      this.recognition.onend = () => {
        this.listening = false;
      };
    }
  }

  ngAfterViewInit() {
    this.scrollToBottom();
  }

async formatMessage(text: string): Promise<SafeHtml> {
  const html = await marked.parse(text, { breaks: true });
  return this.sanitizer.bypassSecurityTrustHtml(html as string);
}

  async sendMessage() {
  const prompt = this.userInput.trim();
  if (!prompt) return;

  this.messages.update(msgs => [...msgs, { sender: 'user', text: this.sanitizer.bypassSecurityTrustHtml(prompt) }]);
  this.userInput = '';

  const token = localStorage.getItem('jwt_token');
  const headers = new HttpHeaders({
    'Authorization': `Bearer ${token ?? ''}`,
    'Content-Type': 'application/json'
  });

  this.http.post<{ response: string }>(
    'https://api.blackhatbadshah.com/api/ai/ask',
    prompt,
    { headers, responseType: 'json' }
  ).subscribe({
    next: async (res) => {
      const formatted = await this.formatMessage(res.response);
      this.messages.update(msgs => [...msgs, { sender: 'bot', text: formatted }]);
      this.speak(res.response);
      setTimeout(() => this.scrollToBottom(), 300);
    },
    error: async () => {
      const formattedError = await this.formatMessage('Error communicating with server.');
      this.messages.update(msgs => [...msgs, { sender: 'bot', text: formattedError }]);
      setTimeout(() => this.scrollToBottom(), 100);
    }
  });
}

  scrollToBottom() {
    if (this.chatMessages?.nativeElement) {
      const container = this.chatMessages.nativeElement;
      container.scrollTop = container.scrollHeight;
    }
  }

  // Start voice input
  startListening() {
    if (this.recognition) {
      this.listening = true;
      this.recognition.start();
    } else {
      alert('Speech Recognition not supported in this browser.');
    }
  }

  // Speak output (TTS)
  speak(text: string) {
    if ('speechSynthesis' in window) {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'en-US';
      speechSynthesis.speak(utterance);
    }
  }
}
