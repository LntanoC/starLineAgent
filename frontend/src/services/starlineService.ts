import { api } from "@/services/api";

export type StarlineInfoStatus = "ANNOUNCED" | "TO_BE_ANNOUNCED";
export type StarlineReminderStatus = "NOT_CREATED" | "PENDING" | "SENDING" | "SENT" | "CANCELLED" | "FAILED";

export interface StarlineEvent {
  id: string;
  artistName: string;
  eventName: string | null;
  infoStatus: StarlineInfoStatus;
  sourceName: string | null;
  sourceUrl: string | null;
  ticketTime: string | null;
  email: string | null;
  remindAt: string | null;
  reminderStatus: StarlineReminderStatus;
  sentAt: string | null;
  createdTime: string;
}

export interface SaveStarlineEventRequest {
  artistName: string;
  eventName?: string;
  infoStatus: StarlineInfoStatus;
  sourceName?: string;
  sourceUrl?: string;
  sourceConfirmed?: boolean;
  ticketTime?: string;
  remindAt?: string;
  email?: string;
}

export const listStarlineEvents = () => api.get<unknown, StarlineEvent[]>("/starline/events");
export const createStarlineEvent = (request: SaveStarlineEventRequest) =>
  api.post<unknown, string>("/starline/events", request);
export const cancelStarlineReminder = (id: string) =>
  api.delete<unknown, void>("/starline/events/" + id + "/reminder");