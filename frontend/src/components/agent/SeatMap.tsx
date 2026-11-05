import { mapBounds, sectionPosition, type SeatViewDocument, type SeatSection } from "@/lib/seatView";

const palette = ["var(--color-accent-2)", "var(--color-info)", "var(--color-warning)", "var(--color-success)", "var(--color-glyph-3)"];

export function priceColor(document: SeatViewDocument, section: SeatSection) {
  const prices = [...new Set(document.show.sections.map((item) => item.priceTier))].sort((a, b) => (b ?? -1) - (a ?? -1));
  return palette[prices.indexOf(section.priceTier) % palette.length];
}

export function SeatMap({ document, selected, depth, lateral, onSelect }: {
  document: SeatViewDocument;
  selected?: SeatSection;
  depth: number;
  lateral: number;
  onSelect: (section: SeatSection) => void;
}) {
  const bounds = mapBounds(document);
  const { stage, sections } = document.show;
  const scale = Math.max(bounds[2], bounds[3]);
  const position = selected ? sectionPosition(selected, depth, lateral) : null;
  return (
    <svg viewBox={bounds.join(" ")} className="min-h-64 w-full" style={{ maxHeight: "65vh" }}
      role="group" aria-label={document.venue.name + "演出分区示意图"}>
      <title>选择分区查看票档与大致位置。全部几何为示意。</title>
      {sections.map((section) => {
        const active = section.code === selected?.code;
        return (
          <g key={section.code} role="button" tabIndex={0} aria-pressed={active}
            aria-label={section.label + (section.priceTier == null ? "，票档待确认" : "，票档 " + section.priceTier + " 元")}
            className="cursor-pointer outline-offset-2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
            onClick={() => onSelect(section)} onKeyDown={(event) => {
              if (event.key === "Enter" || event.key === " ") { event.preventDefault(); onSelect(section); }
            }}>
            <title>{section.label}：{section.priceTier == null ? "票档待确认" : "¥" + section.priceTier}</title>
            <polygon points={section.ring.map((point) => point.join(",")).join(" ")}
              fill={active ? "var(--color-accent)" : priceColor(document, section)} fillOpacity={active ? 1 : 0.45}
              stroke={active ? "var(--color-ink)" : "var(--color-paper)"} strokeWidth={scale * (active ? 0.004 : 0.002)} />
          </g>
        );
      })}
      <g transform={`translate(${stage.x} ${stage.y}) rotate(${stage.rotation})`} pointerEvents="none">
        <rect x={-stage.width / 2} y={-stage.height / 2} width={stage.width} height={stage.height}
          rx={scale * 0.006} fill="var(--color-graphite)" />
        <text transform={`rotate(${-stage.rotation})`} textAnchor="middle" dominantBaseline="middle"
          fill="var(--color-graphite-ink)" fontSize={scale * 0.023}>舞台</text>
      </g>
      {position && <g pointerEvents="none">
        <line x1={position[0]} y1={position[1]} x2={stage.x} y2={stage.y} stroke="var(--color-ink)"
          strokeDasharray={`${scale * 0.008} ${scale * 0.006}`} strokeWidth={scale * 0.002} />
        <circle cx={position[0]} cy={position[1]} r={scale * 0.009} fill="var(--color-ink)"
          stroke="var(--color-paper)" strokeWidth={scale * 0.003} />
        <text x={position[0]} y={position[1] - scale * 0.019} textAnchor="middle" fill="var(--color-ink)"
          fontSize={scale * 0.022}>你的位置</text>
      </g>}
    </svg>
  );
}
