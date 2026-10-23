import { api } from "@/services/api";

export type PlatformSourceStatus = "PENDING" | "APPROVED" | "REJECTED";
export type PlatformFieldStability = "STABLE" | "PARTIAL" | "UNSTABLE";
export type SourceReviewerType = "PRODUCT" | "ENGINEERING";
export interface PlatformSourceReview {
  reviewerName: string; passed: boolean; reviewNote: string | null; reviewedTime: string;
}
export interface StarlinePlatformSource {
  id: string; platformName: string; officialDomain: string;
  publicAccessible: boolean; activityFieldsAvailable: boolean;
  ticketTimeAvailable: boolean; ticketStatusAvailable: boolean;
  fieldStability: PlatformFieldStability; officialIdentityEvidence: string;
  updateFrequency: string; accessBoundary: string; status: PlatformSourceStatus;
  productReview: PlatformSourceReview | null; engineeringReview: PlatformSourceReview | null;
  createdTime: string; updateTime: string;
}
export interface SavePlatformSourceRequest {
  platformName: string; officialDomain: string; publicAccessible: boolean;
  activityFieldsAvailable: boolean; ticketTimeAvailable: boolean; ticketStatusAvailable: boolean;
  fieldStability: PlatformFieldStability; officialIdentityEvidence: string;
  updateFrequency: string; accessBoundary: string;
}
export interface ReviewPlatformSourceRequest {
  reviewerType: SourceReviewerType; reviewerName: string; passed: boolean; reviewNote?: string;
}
const PATH = "/starline/admin/platform-sources";
export const listStarlinePlatformSources = () => api.get<unknown, StarlinePlatformSource[]>(PATH);
export const createStarlinePlatformSource = (body: SavePlatformSourceRequest) => api.post<unknown, string>(PATH, body);
export const updateStarlinePlatformSource = (id: string, body: SavePlatformSourceRequest) => api.put<unknown, void>(PATH + "/" + id, body);
export const reviewStarlinePlatformSource = (id: string, body: ReviewPlatformSourceRequest) => api.post<unknown, void>(PATH + "/" + id + "/reviews", body);
