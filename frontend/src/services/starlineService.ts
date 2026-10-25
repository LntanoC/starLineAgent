import { api } from "@/services/api";

export type StarlineReminderStatus = "ACTIVE" | "CANCELLED";

export interface StarlineEvent {
  id: string;
  artistName: string;
  eventName: string | null;
  sourceName: string | null;
  sourceUrl: string | null;
  ticketTime: string | null;
  reminderStatus: StarlineReminderStatus;
  createdTime: string;
}

export interface SaveStarlineEventRequest {
  artistName: string;
  eventName?: string;
  sourceName?: string;
  sourceUrl?: string;
  sourceConfirmed?: boolean;
  ticketTime?: string;
}

export const listStarlineEvents = () => api.get<unknown, StarlineEvent[]>("/starline/events");
export const createStarlineEvent = (request: SaveStarlineEventRequest) =>
  api.post<unknown, string>("/starline/events", request);
export const cancelStarlineReminder = (id: string) =>
  api.delete<unknown, void>("/starline/events/" + id + "/reminder");

export const getCalendarToken = () => api.get<unknown, string>("/starline/calendar-token");
export const resetCalendarToken = () => api.post<unknown, string>("/starline/calendar-token/reset");
