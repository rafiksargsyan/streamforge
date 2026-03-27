import type { User } from 'firebase/auth';
import { apiRequest } from './client';
import type {
  TranscodingJobCreationRequest,
  TranscodingJobDTO,
  JobLimitsDTO,
  PageResponse,
} from '../types/api.types';

export function listJobs(
  user: User,
  accountId: string,
  page: number,
  size: number,
): Promise<PageResponse<TranscodingJobDTO>> {
  return apiRequest<PageResponse<TranscodingJobDTO>>(
    `/transcoding-job?page=${page}&size=${size}`,
    user,
    { accountId },
  );
}

export function createJob(
  user: User,
  accountId: string,
  body: TranscodingJobCreationRequest,
): Promise<TranscodingJobDTO> {
  return apiRequest<TranscodingJobDTO>('/transcoding-job', user, {
    method: 'POST',
    accountId,
    body: JSON.stringify(body),
  });
}

export function cancelJob(
  user: User,
  accountId: string,
  jobId: string,
): Promise<void> {
  return apiRequest<void>(`/transcoding-job/${jobId}`, user, {
    method: 'DELETE',
    accountId,
  });
}

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export async function getJobLimits(): Promise<JobLimitsDTO> {
  const response = await fetch(`${BASE_URL}/transcoding-job/limits`);
  return response.json() as Promise<JobLimitsDTO>;
}
