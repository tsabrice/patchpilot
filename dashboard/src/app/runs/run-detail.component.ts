import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '@auth0/auth0-angular';
import { filter, switchMap, take } from 'rxjs';

import { RunsService } from './runs.service';
import {
  FindingDetail,
  FindingPipelineStatus,
  GithubPrStatus,
  RunDetail,
  RunStatus,
} from '../shared/api.types';

@Component({
  selector: 'app-run-detail',
  imports: [CommonModule, RouterLink, DatePipe],
  templateUrl: './run-detail.component.html',
})
export class RunDetailComponent implements OnInit {
  private readonly runsService = inject(RunsService);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);

  run = signal<RunDetail | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);

  ngOnInit() {
    // Wait for Auth0 to finish loading before making the API call,
    // for the same reason as RunListComponent — the interceptor needs
    // a valid token before the first request goes out.
    this.auth.isAuthenticated$.pipe(
      filter(authenticated => authenticated),
      take(1),
      // Once authenticated, read the :id param and fetch the run.
      switchMap(() => {
        const id = Number(this.route.snapshot.paramMap.get('id'));
        return this.runsService.getById(id);
      }),
    ).subscribe({
      next: run => {
        this.run.set(run);
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err.status === 404
          ? 'Run not found.'
          : 'Failed to load run details. Is the AI Agent running?');
        this.loading.set(false);
      },
    });
  }

  // Maps a run status to a Tailwind badge colour class.
  runStatusClass(status: RunStatus): string {
    const map: Record<RunStatus, string> = {
      PENDING:     'bg-gray-100 text-gray-600',
      IN_PROGRESS: 'bg-blue-100 text-blue-700',
      COMPLETED:   'bg-green-100 text-green-700',
      FAILED:      'bg-red-100 text-red-700',
    };
    return map[status] ?? 'bg-gray-100 text-gray-600';
  }

  // Maps a finding pipeline status to a Tailwind badge colour class.
  findingStatusClass(status: FindingPipelineStatus): string {
    const map: Record<FindingPipelineStatus, string> = {
      QUEUED:       'bg-gray-100 text-gray-600',
      FETCHING_FILE:'bg-blue-100 text-blue-600',
      CALLING_CLAUDE:'bg-purple-100 text-purple-700',
      CREATING_PR:  'bg-blue-100 text-blue-700',
      COMPLETED:    'bg-green-100 text-green-700',
      FAILED:       'bg-red-100 text-red-700',
      SKIPPED:      'bg-gray-100 text-gray-500',
    };
    return map[status] ?? 'bg-gray-100 text-gray-600';
  }

  // Maps SonarQube severity to a Tailwind badge colour class.
  severityClass(severity: string): string {
    const map: Record<string, string> = {
      BLOCKER:  'bg-red-100 text-red-800',
      CRITICAL: 'bg-orange-100 text-orange-800',
      MAJOR:    'bg-amber-100 text-amber-800',
      MINOR:    'bg-blue-100 text-blue-700',
      INFO:     'bg-gray-100 text-gray-600',
    };
    return map[severity] ?? 'bg-gray-100 text-gray-600';
  }

  // Strips the long Maven path prefix from a component, keeping just the
  // short class name for display. e.g. "demo-app:src/main/java/.../Foo.java" → "Foo.java"
  shortComponent(component: string): string {
    const parts = component.split('/');
    return parts[parts.length - 1];
  }

  // Converts a 0–1 confidence score to a percentage string.
  confidenceLabel(score: number | null): string {
    if (score === null) return '—';
    return `${Math.round(score * 100)}%`;
  }

  // Returns true when the PR status represents an open PR.
  isPrOpen(status: GithubPrStatus | null): boolean {
    return status === 'PR_OPEN';
  }
}
