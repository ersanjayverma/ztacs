import { Component, inject } from '@angular/core';
import { AuthService } from '../../service/authservice';
import { Router } from '@angular/router';

@Component({
  selector: 'app-header',
  standalone: true,
  templateUrl: './header.html',
  styleUrls: ['./header.css']
})
export class Header {
  authService = inject(AuthService);
  router = inject(Router);

  handleAuthClick() {
    if (this.authService.isLoggedInSignal()) {
      this.authService.logout();
      this.router.navigate(['/login']);
    } else {
      this.router.navigate(['/login']);
    }
  }
}

