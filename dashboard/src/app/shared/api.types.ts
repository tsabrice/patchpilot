// Types matching the AI Agent REST API responses (port 8081).

export type RunStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
export type FindingPipelineStatus =
  | 'QUEUED' | 'FETCHING_FILE' | 'CALLING_CLAUDE' | 'CREATING_PR'
  | 'COMPLETED' | 'FAILED' | 'SKIPPED';

export interface PipelineRunSummary {
  id: number;
  status: RunStatus;
  projectKey: string;
  branch: string;
  startedAt: string;       // ISO-8601
  finishedAt: string | null;
  findingsCount: number;
  generationsCount: number;
  openPrsCount: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;   // current page (0-indexed)
  size: number;
}
