import { Routes } from '@angular/router';
import { LoginComponent } from './components/login/login';
import { RegisterComponent } from './components/register/register';
import { authGuard } from './interceptor/authguard';

export const appRoutes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'register', component: RegisterComponent },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./home/home').then(m => m.Home)
  },
  {
    path: 'chess',
    canActivate: [authGuard],
   loadComponent: () => import('./components/chess/chess').then(m => m.ChessComponent)
  },
  { path: '**', redirectTo: 'login' }
];
