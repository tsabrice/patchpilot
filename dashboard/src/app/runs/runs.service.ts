import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { PageResponse, PipelineRunSummary } from '../shared/api.types';

@Injectable({ providedIn: 'root' })
export class RunsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/api/runs`;

  list(page = 0, size = 20): Observable<PageResponse<PipelineRunSummary>> {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size)
      .set('sort', 'startedAt,desc');
    return this.http.get<PageResponse<PipelineRunSummary>>(this.base, { params });
  }
}
