import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { HTTP_INTERCEPTORS, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { AuthHttpInterceptor, provideAuth0 } from '@auth0/auth0-angular';

import { routes } from './app.routes';
import { environment } from '../environments/environment';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),

    // HttpClient is required by both the Auth0 SDK (JWKS fetch) and our API calls.
    // withInterceptorsFromDi() enables the Auth0 AuthHttpInterceptor registered below.
    provideHttpClient(withInterceptorsFromDi()),

    // Auth0 PKCE flow for Angular SPAs.
    // The SDK handles token storage, silent refresh, and redirect callbacks.
    provideAuth0({
      domain: environment.auth0.domain,
      clientId: environment.auth0.clientId,
      authorizationParams: {
        redirect_uri: environment.auth0.redirectUri,
        audience: environment.auth0.audience,
      },
      // Automatically attach the Bearer JWT to any request matching httpInterceptor.allowedList
      httpInterceptor: {
        allowedList: [
          // All AI Agent API routes require a valid JWT
          { uri: `${environment.apiUrl}/api/*` },
        ],
      },
    }),

    // provideAuth0() configures the interceptor rules but does NOT register it.
    // This DI token is what actually adds the interceptor to the HttpClient chain.
    { provide: HTTP_INTERCEPTORS, useClass: AuthHttpInterceptor, multi: true },
  ],
};
