import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from '@auth0/auth0-angular';
import { filter, switchMap, map, tap, take } from 'rxjs';

// Functional guard (Angular 15+ style — no class needed).
// Redirects unauthenticated users to Auth0's Universal Login page.
//
// Why filter on isLoading$?
// On startup (and after the Auth0 callback redirect), isLoading$ is true
// while the SDK checks for an existing session / exchanges the auth code.
// Checking isAuthenticated$ before loading completes always returns false,
// causing a premature loginWithRedirect() and a redirect loop.
// Waiting for isLoading$ = false guarantees the SDK has settled before we
// check whether the user is actually authenticated.
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);

  return auth.isLoading$.pipe(
    filter(loading => !loading),
    take(1),
    switchMap(() => auth.isAuthenticated$),
    take(1),
    tap(authenticated => {
      if (!authenticated) auth.loginWithRedirect();
    }),
    map(authenticated => authenticated),
  );
};
