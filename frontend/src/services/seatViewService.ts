import { api } from "@/services/api";
import type { SeatViewDocument } from "@/lib/seatView";

export const listSeatViews = (admin = false, signal?: AbortSignal) =>
  api.get<unknown, SeatViewDocument[]>("/starline/seat-view/" + (admin ? "admin/" : "") + "catalog", { signal });
export const saveSeatView = (document: SeatViewDocument) =>
  api.put<unknown, void>("/starline/seat-view/admin/catalog", document);

export interface SeatPhoto {
  id: string;
  showId: string;
  sectionCode: string;
  row: number | null;
  seat: number | null;
  caption: string;
  createdAt: string;
  owned: boolean;
}

export const listSeatPhotos = (showId: string, sectionCode: string | undefined, row: number | undefined, seat: number | undefined, page: number, signal?: AbortSignal) =>
  api.get<unknown, SeatPhoto[]>("/starline/seat-view/photos", { params: { showId, sectionCode, row, seat, page }, signal });
export const uploadSeatPhoto = (data: FormData) => api.post<unknown, void>("/starline/seat-view/photos", data);
export const getSeatPhoto = (id: string, signal?: AbortSignal) =>
  api.get<unknown, Blob>(`/starline/seat-view/photos/${encodeURIComponent(id)}/content`, { responseType: "blob", signal });
