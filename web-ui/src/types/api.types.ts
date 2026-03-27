export interface UserDTO {
  id: string;
  accountId: string;
  fullName: string;
}

export interface ApiKeyDTO {
  id: string;
  description: string;
  enabled: boolean;
  key?: string;
  createdAt: string;
  lastUsedAt?: string;
}

export type Lang =
  | 'EN' | 'ES' | 'FR' | 'DE' | 'IT' | 'PT' | 'RU' | 'ZH' | 'JA' | 'KO'
  | 'AR' | 'HI' | 'BN' | 'TR' | 'PL' | 'NL' | 'SV' | 'NO' | 'DA' | 'FI'
  | 'CS' | 'SK' | 'HU' | 'RO' | 'UK' | 'EL' | 'HE' | 'FA' | 'ID' | 'MS'
  | 'TH' | 'VI' | 'SR' | 'HR' | 'BG' | 'LT' | 'LV' | 'ET'
  | 'BE' | 'HY' | 'MK' | 'NB' | 'SL';

export interface VideoRendition {
  resolution: number;
  fileName: string;
}

export interface VideoTranscodeSpec {
  stream: number;
  renditions: VideoRendition[];
}

export interface AudioTranscodeSpec {
  stream: number;
  bitrateKbps: number;
  channels: number;
  lang: Lang;
  name: string;
  fileName: string;
}

export interface TextTranscodeSpec {
  lang: Lang;
  name: string;
  fileName: string;
  src: string;
}

export interface TranscodeSpec {
  video: VideoTranscodeSpec;
  audios: AudioTranscodeSpec[];
  texts: TextTranscodeSpec[];
}

export type JobStatus =
  | 'SUBMITTED'
  | 'QUEUED'
  | 'RECEIVED'
  | 'IN_PROGRESS'
  | 'RETRYING'
  | 'SUCCESS'
  | 'FAILURE'
  | 'CANCELLED';

export interface TranscodingJobDTO {
  id: string;
  videoUrl: string;
  status: JobStatus;
  spec: TranscodeSpec;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
  dashManifestUrl?: string;
  hlsManifestUrl?: string;
  failureReason?: string;
}

export interface TranscodingJobCreationRequest {
  videoURL: string;
  spec: TranscodeSpec;
}

export interface JobLimitsDTO {
  maxJobs: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
