import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { mapBounds, sectionPosition, type SeatSection, type SeatViewDocument } from "@/lib/seatView";
import { Button } from "@/components/ui/button";

type ViewMode = "overview" | "audience" | "phone";

export function SeatPerspective({ document, section, depth, lateral, standing, onSelect, initialMode = "overview" }: {
  document: SeatViewDocument;
  section: SeatSection;
  depth: number;
  lateral: number;
  standing: boolean;
  onSelect: (section: SeatSection) => void;
  initialMode?: ViewMode;
}) {
  const container = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement | null>(null);
  const viewRef = useRef<{ camera: THREE.PerspectiveCamera; render: () => void } | null>(null);
  const [error, setError] = useState(false);
  const [mode, setMode] = useState<ViewMode>(initialMode);
  const [zoom, setZoom] = useState(1);
  const zoomRef = useRef(zoom);
  zoomRef.current = zoom;
  const [portrait, setPortrait] = useState(true);
  const [targetMode, setTargetMode] = useState<"stage" | "person">("person");
  const [reset, setReset] = useState(0);
  const selectRef = useRef(onSelect);
  selectRef.current = onSelect;

  useEffect(() => {
    const element = container.current;
    if (!element) return;
    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
    } catch {
      setError(true);
      return;
    }
    setError(false);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    element.appendChild(renderer.domElement);
    canvas.current = renderer.domElement;
    const scene = new THREE.Scene();
    scene.background = new THREE.Color("#e7ebe6");
    scene.add(new THREE.HemisphereLight(0xffffff, 0x88958a, 2));
    const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 1000);
    const bounds = mapBounds(document);
    const scale = Math.max(bounds[2], bounds[3]);
    const centerX = bounds[0] + bounds[2] / 2;
    const centerY = bounds[1] + bounds[3] / 2;
    const point = ([x, y]: number[]) => new THREE.Vector3((x - centerX) / scale * 100, 0, (y - centerY) / scale * 100);
    // 旧数据没有高度和比例尺，按层级生成统一的示意坡度。
    const height = (item: SeatSection, fraction: number) => item.level === 1 ? 0 : (item.level - 2) * 8 + 2 + fraction * 7;
    const surfaces: THREE.Mesh[] = [];
    for (const item of document.show.sections) {
      const vertices = item.ring.map((p, index) => {
        const v = point(p);
        const fromFront = (index - item.frontEdge + 4) % 4;
        v.y = height(item, fromFront === 0 || fromFront === 1 ? 0 : 1);
        return v;
      });
      const geometry = new THREE.BufferGeometry().setFromPoints([vertices[0], vertices[1], vertices[2], vertices[0], vertices[2], vertices[3]]);
      geometry.computeVertexNormals();
      const mesh = new THREE.Mesh(geometry, new THREE.MeshLambertMaterial({ color: item.code === section.code ? 0x638b72 : 0x9daea0, side: THREE.DoubleSide }));
      mesh.userData.section = item;
      surfaces.push(mesh);
      scene.add(mesh);

      const lines: THREE.Vector3[] = [];
      for (let row = 1; row < 12; row++) {
        const fraction = row / 12;
        for (const side of [0, 100]) {
          const p = point(sectionPosition(item, fraction * 100, side));
          p.y = height(item, fraction) + 0.04;
          lines.push(p);
        }
      }
      scene.add(new THREE.LineSegments(new THREE.BufferGeometry().setFromPoints(lines), new THREE.LineBasicMaterial({ color: 0x657a69 })));
    }
    const ground = new THREE.Mesh(new THREE.PlaneGeometry(120, 120), new THREE.MeshLambertMaterial({ color: 0xd1d9ce, side: THREE.DoubleSide }));
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.1;
    scene.add(ground);
    const stage = document.show.stage;
    const stageMesh = new THREE.Mesh(new THREE.BoxGeometry(stage.width / scale * 100, 1.2, stage.height / scale * 100), new THREE.MeshLambertMaterial({ color: 0x374b40 }));
    stageMesh.position.copy(point([stage.x, stage.y]));
    stageMesh.position.y = 0.6;
    stageMesh.rotation.y = -stage.rotation * Math.PI / 180;
    scene.add(stageMesh);
    // 示意人物用于比较取景构图，比例沿用归一化场馆坐标，并非真实身高测量。
    const person = new THREE.Group();
    const body = new THREE.Mesh(new THREE.CylinderGeometry(0.2, 0.3, 1.1, 8), new THREE.MeshLambertMaterial({ color: 0xd8c9a2 }));
    body.position.y = 0.75;
    const head = new THREE.Mesh(new THREE.SphereGeometry(0.22, 8, 6), new THREE.MeshLambertMaterial({ color: 0xe6ceb3 }));
    head.position.y = 1.48;
    person.add(body, head);
    person.position.copy(stageMesh.position);
    person.position.y = 1.2;
    scene.add(person);
    const position = point(sectionPosition(section, depth, lateral));
    position.y = height(section, depth / 100) + (standing ? 1.65 : 1.15);
    const target = point([stage.x, stage.y]);
    target.y = targetMode === "person" && mode === "phone" ? 2.5 : 0.6;
    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = false;
    controls.enablePan = mode === "overview";
    if (mode === "overview") {
      camera.position.set(105, 95, 105);
      controls.target.set(0, 0, 0);
      controls.minDistance = 20;
      controls.maxDistance = 280;
    } else {
      // 环顾时相机保持在选中位置，目标点绕相机旋转。
      camera.position.copy(position);
      if (target.distanceTo(position) < 0.01) target.z += 0.01;
      controls.target.copy(target);
      controls.enableZoom = false;
      controls.enablePan = false;
      controls.enableRotate = false;
      if (mode === "phone") camera.zoom = zoomRef.current;
    }
    camera.updateProjectionMatrix();
    controls.update();
    const render = () => renderer.render(scene, camera);
    viewRef.current = { camera, render };
    controls.addEventListener("change", render);
    const resize = () => {
      const width = element.clientWidth;
      const height = element.clientHeight;
      if (!width || !height) return;
      renderer.setSize(width, height);
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
      render();
    };
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    const raycaster = new THREE.Raycaster();
    let pointerStart: { x: number; y: number } | null = null;
    let lastPointer: { x: number; y: number } | null = null;
    const down = (event: PointerEvent) => { pointerStart = { x: event.clientX, y: event.clientY }; };
    const move = (event: PointerEvent) => {
      if (mode === "overview" || !pointerStart || event.buttons === 0) return;
      const previous = lastPointer ?? pointerStart;
      const direction = new THREE.Spherical().setFromVector3(controls.target.clone().sub(camera.position));
      direction.theta -= (event.clientX - previous.x) * 0.005;
      direction.phi = THREE.MathUtils.clamp(direction.phi + (event.clientY - previous.y) * 0.005, 0.05, Math.PI - 0.05);
      controls.target.copy(camera.position).add(new THREE.Vector3().setFromSpherical(direction));
      lastPointer = { x: event.clientX, y: event.clientY };
      controls.update();
      render();
    };
    const up = (event: PointerEvent) => {
      const start = pointerStart;
      pointerStart = null;
      lastPointer = null;
      if (mode !== "overview" || !start || Math.hypot(event.clientX - start.x, event.clientY - start.y) > 5) return;
      const rect = renderer.domElement.getBoundingClientRect();
      raycaster.setFromCamera(new THREE.Vector2((event.clientX - rect.left) / rect.width * 2 - 1, 1 - (event.clientY - rect.top) / rect.height * 2), camera);
      const hit = raycaster.intersectObjects(surfaces)[0];
      if (hit) selectRef.current(hit.object.userData.section as SeatSection);
    };
    renderer.domElement.addEventListener("pointerdown", down);
    renderer.domElement.addEventListener("pointermove", move);
    renderer.domElement.addEventListener("pointerup", up);
    resize();
    return () => {
      observer.disconnect();
      controls.removeEventListener("change", render);
      controls.dispose();
      renderer.domElement.removeEventListener("pointerdown", down);
      renderer.domElement.removeEventListener("pointermove", move);
      renderer.domElement.removeEventListener("pointerup", up);
      scene.traverse((object) => {
        if (object instanceof THREE.Mesh || object instanceof THREE.LineSegments) {
          object.geometry.dispose();
          if (Array.isArray(object.material)) object.material.forEach((material) => material.dispose());
          else object.material.dispose();
        }
      });
      canvas.current = null;
      viewRef.current = null;
      renderer.dispose();
      renderer.domElement.remove();
    };
  }, [document, section, depth, lateral, standing, mode, reset, targetMode]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view || mode !== "phone") return;
    view.camera.zoom = zoom;
    view.camera.updateProjectionMatrix();
    view.render();
  }, [zoom, mode]);

  function download() {
    const image = canvas.current;
    if (!image) return;
    image.toBlob((blob) => {
      if (!blob) return;
      const link = window.document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = "seat-view.png";
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    });
  }

  return <div className="space-y-3">
    <div className="flex flex-wrap gap-2" aria-label="三维视角模式">
      {([ ["overview", "场馆总览"], ["audience", "观众视角"], ["phone", "手机取景"] ] as const).map(([value, label]) =>
        <Button key={value} size="sm" variant={mode === value ? "default" : "outline"} aria-pressed={mode === value} onClick={() => setMode(value)}>{label}</Button>)}
      <Button size="sm" variant="outline" onClick={() => setReset((value) => value + 1)}>重置视角</Button>
    </div>
    <div className={mode === "phone" ? (portrait ? "mx-auto w-full max-w-xs" : "mx-auto w-full max-w-xl") : "w-full"}>
      <div className="relative w-full overflow-hidden rounded-lg" style={{ aspectRatio: mode === "phone" ? (portrait ? "3 / 4" : "4 / 3") : "16 / 10" }} aria-label="场馆三维示意图">
        <div ref={container} className="h-full w-full" />
        {error && <p role="alert" className="absolute inset-0 flex items-center justify-center bg-paper p-4 text-sm">当前浏览器无法显示三维视角，请使用二维座位图。</p>}
      </div>
    </div>
    <p className="text-xs text-muted-foreground">{mode === "overview" ? "拖动旋转、滚轮缩放，点击分区选择位置。" : "拖动环顾；画面由示意几何生成。"}</p>
    {mode === "phone" && <div className="space-y-3">
      <label className="block text-sm">取景放大 <output>{zoom.toFixed(1)}×</output><input className="block w-full accent-accent" type="range" min="1" max="30" step="0.1" value={zoom} onChange={(event) => setZoom(Number(event.target.value))} /></label>
      <div className="flex flex-wrap gap-2" aria-label="预设变焦倍数">{[1, 2, 5, 10, 30].map((value) =>
        <Button key={value} size="sm" variant={zoom === value ? "default" : "outline"} aria-pressed={zoom === value} onClick={() => setZoom(value)}>{value}×</Button>)}</div>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant={portrait ? "default" : "outline"} aria-pressed={portrait} onClick={() => setPortrait(true)}>竖拍 3:4</Button>
        <Button size="sm" variant={!portrait ? "default" : "outline"} aria-pressed={!portrait} onClick={() => setPortrait(false)}>横拍 4:3</Button>
        <Button size="sm" variant={targetMode === "person" ? "default" : "outline"} aria-pressed={targetMode === "person"} onClick={() => setTargetMode("person")}>对准示意人物</Button>
        <Button size="sm" variant={targetMode === "stage" ? "default" : "outline"} aria-pressed={targetMode === "stage"} onClick={() => setTargetMode("stage")}>对准舞台中央</Button>
        <Button size="sm" variant="outline" onClick={download}>导出取景图</Button>
      </div>
      <p className="text-xs text-muted-foreground">人物、变焦和场馆均为构图示意，不代表真实照片、设备焦距、清晰度或遮挡。</p>
    </div>}
  </div>;
}
