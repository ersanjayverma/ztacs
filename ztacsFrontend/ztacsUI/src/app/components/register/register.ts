import { Component } from '@angular/core';
import { AuthService } from '../../service/authservice';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpResponse } from '@angular/common/http';

@Component({
  standalone: true,
  selector: 'app-register',
  templateUrl: './register.html',
  styleUrls: ['./register.css'],
  imports: [CommonModule, FormsModule]
})
export class RegisterComponent {
  username = '';
  email = '';
  firstName = '';
  lastName = '';
  password = '';

  constructor(private authService: AuthService, private router: Router) {}

  register() {
    const newUser = {
      username: this.username,
      email: this.email,
      firstName: this.firstName,
      lastName: this.lastName,
      password: this.password
    };

 this.authService.register(newUser).subscribe({
    next: (res) => {
      if (res.status ==200 ) {
        alert(' Registration successful. Please login.');
        this.router.navigate(['/login']);
      } else {
        alert(' Registration may not have completed properly.');
        console.warn('Unexpected response:', res);
      }
    },
    error: err => {
      console.error('Registration failed:', err);
      alert(' Registration failed. Please try again.');
    }
  });
  }
}
