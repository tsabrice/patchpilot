import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { StatsSummary } from '../shared/api.types';

@Injectable({ providedIn: 'root' })
export class StatsService {
  private readonly http = inject(HttpClient);

  getSummary(): Observable<StatsSummary> {
    return this.http.get<StatsSummary>(`${environment.apiUrl}/api/stats/summary`);
  }
}
