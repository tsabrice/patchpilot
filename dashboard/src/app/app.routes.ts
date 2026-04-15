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
  {
    path: 'runs/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./runs/run-detail.component').then(m => m.RunDetailComponent),
  },
  {
    path: 'stats',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./stats/stats.component').then(m => m.StatsComponent),
  },
  {
    path: '**',
    redirectTo: 'runs',
  },
];
