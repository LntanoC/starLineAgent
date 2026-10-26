import { api } from "@/services/api";

export type PlatformSourceStatus = "ACTIVE" | "DISABLED";
export type PlatformFieldStability = "STABLE" | "PARTIAL" | "UNSTABLE";
export type PlatformSourceTier = "GOV" | "TICKETING";
export interface StarlinePlatformSource {
  id: string; platformName: string; officialDomain: string; sourceTier: PlatformSourceTier;
  publicAccessible: boolean; activityFieldsAvailable: boolean;
  ticketTimeAvailable: boolean; ticketStatusAvailable: boolean;
  fieldStability: PlatformFieldStability; officialIdentityEvidence: string;
  updateFrequency: string; accessBoundary: string; status: PlatformSourceStatus;
  createdTime: string; updateTime: string;
}
export interface SavePlatformSourceRequest {
  platformName: string; officialDomain: string; sourceTier: PlatformSourceTier; publicAccessible: boolean;
  activityFieldsAvailable: boolean; ticketTimeAvailable: boolean; ticketStatusAvailable: boolean;
  fieldStability: PlatformFieldStability; officialIdentityEvidence: string;
  updateFrequency: string; accessBoundary: string;
}
export interface SetStarlinePlatformSourceEnabledRequest { enabled: boolean; }
const PATH = "/starline/admin/platform-sources";
export const listStarlinePlatformSources = () => api.get<unknown, StarlinePlatformSource[]>(PATH);
export const createStarlinePlatformSource = (body: SavePlatformSourceRequest) => api.post<unknown, string>(PATH, body);
export const updateStarlinePlatformSource = (id: string, body: SavePlatformSourceRequest) => api.put<unknown, void>(PATH + "/" + id, body);
export const setStarlinePlatformSourceEnabled = (id: string, body: SetStarlinePlatformSourceEnabledRequest) => api.put<unknown, void>(PATH + "/" + id + "/enabled", body);
