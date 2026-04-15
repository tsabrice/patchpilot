// Types matching the AI Agent REST API responses (port 8081).

export type RunStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
export type FindingPipelineStatus =
  | 'QUEUED' | 'FETCHING_FILE' | 'CALLING_CLAUDE' | 'CREATING_PR'
  | 'COMPLETED' | 'FAILED' | 'SKIPPED';
export type GithubPrStatus = 'PENDING' | 'PR_OPEN' | 'PR_MERGED' | 'PR_CLOSED' | 'FAILED';

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

export interface FindingDetail {
  id: number;
  sonarIssueKey: string;
  ruleKey: string;
  severity: string;              // BLOCKER | CRITICAL | MAJOR | MINOR | INFO
  component: string;             // file path
  line: number | null;
  message: string;
  pipelineStatus: FindingPipelineStatus;
  confidenceScore: number | null;  // 0.000–1.000; null until Claude runs
  prNumber: number | null;
  prUrl: string | null;
  prStatus: GithubPrStatus | null;
  prTitleEn: string | null;
}

export interface RunDetail {
  id: number;
  status: RunStatus;
  projectKey: string;
  branch: string;
  commitSha: string | null;
  startedAt: string;
  finishedAt: string | null;
  findings: FindingDetail[];
}

export interface StatsSummary {
  totalRuns: number;
  completedRuns: number;
  failedRuns: number;
  totalFindings: number;
  fixedFindings: number;
  openPrs: number;
  avgConfidence: number | null;  // 0.000–1.000; null until first generation
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;   // current page (0-indexed)
  size: number;
}
