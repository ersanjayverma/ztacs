import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';

interface TokenResponse {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  refresh_expires_in: number;
  token_type: string;
  scope: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  token = signal<string | null>(localStorage.getItem('jwt_token'));
  refreshToken = signal<string | null>(localStorage.getItem('refresh_token'));
 isLoggedInSignal = signal<boolean>(this.hasToken());
  login(username: string, password: string) {
    return this.http.post<TokenResponse>(
      'https://api.blackhatbadshah.com/api/auth/get-token',
      { "username": username,"password":  password }
    ).subscribe({
      next: res => {
        localStorage.setItem('jwt_token', res.access_token);
        localStorage.setItem('refresh_token', res.refresh_token);
        this.token.set(res.access_token);
        this.refreshToken.set(res.refresh_token);
        this.isLoggedInSignal.set(true); 
        this.router.navigateByUrl('/');

      },
      error: err => {
         console.error('Login error:', err);
        alert('Invalid credentials.');
      }
    });
  }
   private hasToken(): boolean {
    return !!localStorage.getItem('jwt_token');
  }
 register(user: any) {
  return this.http.post(
    'https://api.blackhatbadshah.com/api/auth/register',
    user,
    { observe: 'response', responseType: 'text' } 
  );
}

  logout() {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('refresh_token');
    this.token.set(null);
    this.refreshToken.set(null);
    this.isLoggedInSignal.set(false); 
    this.router.navigateByUrl('/login');
  }

  isLoggedIn(): boolean {
    return !!this.token();
  }

  getToken(): string | null {
    return this.token();
  }

  getRefreshToken(): string | null {
    return this.refreshToken();
  }
}
