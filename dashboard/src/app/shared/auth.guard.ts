import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from '@auth0/auth0-angular';
import { map, tap } from 'rxjs';

// Functional guard (Angular 15+ style — no class needed).
// Redirects unauthenticated users to Auth0's Universal Login page.
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);

  return auth.isAuthenticated$.pipe(
    tap(isAuthenticated => {
      if (!isAuthenticated) {
        auth.loginWithRedirect();
      }
    }),
    map(isAuthenticated => isAuthenticated),
  );
};
