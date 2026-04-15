import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '@auth0/auth0-angular';
import { filter, take } from 'rxjs';

import { StatsService } from './stats.service';
import { StatsSummary } from '../shared/api.types';

@Component({
  selector: 'app-stats',
  imports: [CommonModule, RouterLink, DecimalPipe],
  templateUrl: './stats.component.html',
})
export class StatsComponent implements OnInit {
  private readonly statsService = inject(StatsService);
  private readonly auth = inject(AuthService);

  stats = signal<StatsSummary | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);

  ngOnInit() {
    this.auth.isAuthenticated$.pipe(
      filter(authenticated => authenticated),
      take(1),
    ).subscribe(() => this.load());
  }

  load() {
    this.loading.set(true);
    this.error.set(null);
    this.statsService.getSummary().subscribe({
      next: s => {
        this.stats.set(s);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load stats. Is the AI Agent running?');
        this.loading.set(false);
      },
    });
  }

  // Fix rate as a 0–100 number, one decimal place. Returns null when no findings.
  fixRate(s: StatsSummary): number | null {
    if (s.totalFindings === 0) return null;
    return (s.fixedFindings / s.totalFindings) * 100;
  }

  // Run success rate as a 0–100 number. Returns null when no runs.
  runSuccessRate(s: StatsSummary): number | null {
    if (s.totalRuns === 0) return null;
    return (s.completedRuns / s.totalRuns) * 100;
  }

  avgConfidencePct(s: StatsSummary): string {
    if (s.avgConfidence === null) return '—';
    return `${Math.round(s.avgConfidence * 100)}%`;
  }
}
