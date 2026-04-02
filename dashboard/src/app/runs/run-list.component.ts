import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '@auth0/auth0-angular';
import { filter, take } from 'rxjs';

import { RunsService } from './runs.service';
import { PipelineRunSummary, RunStatus } from '../shared/api.types';

@Component({
  selector: 'app-run-list',
  imports: [CommonModule, RouterLink, DatePipe],
  templateUrl: './run-list.component.html',
})
export class RunListComponent implements OnInit {
  private readonly runsService = inject(RunsService);
  private readonly auth = inject(AuthService);

  runs = signal<PipelineRunSummary[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);
  totalElements = signal(0);
  currentPage = signal(0);
  readonly pageSize = 20;

  ngOnInit() {
    // Wait for the Auth0 SDK to finish processing the callback and store the
    // access token before making any API calls. Without this, the interceptor
    // calls getTokenSilently() before the token is available and sends the
    // request without an Authorization header, causing a 401.
    this.auth.isAuthenticated$.pipe(
      filter(authenticated => authenticated),
      take(1),
    ).subscribe(() => this.loadPage(0));
  }

  loadPage(page: number) {
    this.loading.set(true);
    this.error.set(null);
    this.runsService.list(page, this.pageSize).subscribe({
      next: res => {
        this.runs.set(res.content);
        this.totalElements.set(res.totalElements);
        this.currentPage.set(res.number);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load pipeline runs. Is the AI Agent running?');
        this.loading.set(false);
      },
    });
  }

  totalPages() {
    return Math.ceil(this.totalElements() / this.pageSize);
  }

  // Maps a run status to a Tailwind badge colour class.
  statusClass(status: RunStatus): string {
    const map: Record<RunStatus, string> = {
      PENDING:     'bg-gray-100 text-gray-600',
      IN_PROGRESS: 'bg-blue-100 text-blue-700',
      COMPLETED:   'bg-green-100 text-green-700',
      FAILED:      'bg-red-100 text-red-700',
    };
    return map[status] ?? 'bg-gray-100 text-gray-600';
  }
}
