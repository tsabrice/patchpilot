import { Routes } from '@angular/router';
import { authGuard } from './shared/auth.guard';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'runs',
    pathMatch: 'full',
  },
  {
    path: 'runs',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./runs/run-list.component').then(m => m.RunListComponent),
  },
  // Placeholder for run detail — Day 17
  {
    path: 'runs/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./runs/run-list.component').then(m => m.RunListComponent),
  },
  {
    path: '**',
    redirectTo: 'runs',
  },
];
