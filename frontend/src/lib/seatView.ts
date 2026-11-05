export type Point = [number, number];
export interface SeatSection {
  code: string;
  label: string;
  level: number;
  priceTier: number | null;
  ring: Point[];
  frontEdge: number;
}
export interface SeatViewDocument {
  venue: { id: string; name: string; city: string; aliases: string[] };
  show: {
    id: string;
    name: string;
    startsAt: string | null;
    status: "DRAFT" | "PUBLISHED" | "ARCHIVED";
    provenance: "DEMO" | "REVIEWED_SCHEMATIC";
    sourceUrl: string;
    sourceNote: string;
    stageLayout: "END" | "CENTER" | "T";
    stage: { x: number; y: number; width: number; height: number; rotation: number };
    sections: SeatSection[];
  };
}

export function percentage(value: string | number | null, fallback = 50): number {
  if (value === null || value === "") return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, Math.min(100, parsed)) : fallback;
}

/** 环顺序及凸性由服务端校验；指定前沿后，两次插值保证标记留在分区内部。 */
export function sectionPosition(section: SeatSection, depth: number, lateral: number): Point {
  const ring = section.ring;
  const a = ring[section.frontEdge];
  const b = ring[(section.frontEdge + 1) % 4];
  const c = ring[(section.frontEdge + 2) % 4];
  const d = ring[(section.frontEdge + 3) % 4];
  const t = percentage(depth) / 100;
  const u = percentage(lateral) / 100;
  return [0, 1].map((axis) =>
    (a[axis] * (1 - t) + d[axis] * t) * (1 - u) + (b[axis] * (1 - t) + c[axis] * t) * u
  ) as Point;
}

export function mapBounds(document: SeatViewDocument): [number, number, number, number] {
  const { stage, sections } = document.show;
  // 舞台的旋转也必须计入画布边界，避免一端舞台在手机上被截掉。
  const radians = stage.rotation * Math.PI / 180;
  const stagePoints = [-1, 1].flatMap((x) => [-1, 1].map((y): Point => [
    stage.x + x * stage.width / 2 * Math.cos(radians) - y * stage.height / 2 * Math.sin(radians),
    stage.y + x * stage.width / 2 * Math.sin(radians) + y * stage.height / 2 * Math.cos(radians)
  ]));
  const points = [...sections.flatMap((section) => section.ring), ...stagePoints];
  const xs = points.map((point) => point[0]);
  const ys = points.map((point) => point[1]);
  const minX = Math.min(...xs), minY = Math.min(...ys);
  const width = Math.max(...xs) - minX, height = Math.max(...ys) - minY;
  const padding = Math.max(width, height) * 0.07;
  return [minX - padding, minY - padding, width + padding * 2, height + padding * 2];
}

/** 非法或已下架的深链保留为未匹配，不静默切换成另一场演出。 */
export function resolveDocument(catalog: SeatViewDocument[], params: URLSearchParams): SeatViewDocument | undefined {
  const venue = params.get("venue");
  const show = params.get("show");
  if (show) return catalog.find((entry) => entry.show.id === show && (!venue || entry.venue.id === venue));
  if (venue) return catalog.find((entry) => entry.venue.id === venue || entry.venue.name === venue || entry.venue.aliases.includes(venue));
  return catalog[0];
}

export function seatSearch(document: SeatViewDocument, section: string, depth: number, lateral: number): URLSearchParams {
  return new URLSearchParams({ venue: document.venue.id, show: document.show.id, section,
    depth: String(percentage(depth)), lateral: String(percentage(lateral)) });
}
