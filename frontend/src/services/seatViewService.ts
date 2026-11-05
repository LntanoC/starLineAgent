import { api } from "@/services/api";
import type { SeatViewDocument } from "@/lib/seatView";

export const listSeatViews = (admin = false, signal?: AbortSignal) =>
  api.get<unknown, SeatViewDocument[]>("/starline/seat-view/" + (admin ? "admin/" : "") + "catalog", { signal });
export const saveSeatView = (document: SeatViewDocument) =>
  api.put<unknown, void>("/starline/seat-view/admin/catalog", document);
