# 演唱会场馆座位视角系统 设计方案（从简入深）

> 面向执行者（Codex）的施工单。本文分三期，每期可独立上线、独立验收，**不要跨期提前实现**。
>
> - 版本：v1
> - 日期：2026-10-29
> - 关联：starline 已具备 `search_artist_events` 演出查询能力（能拿到场馆/时间/坐标），本系统作为其下游的「座位视角」延伸场景
> - 参考实现：视野 Live（shijiao.xingpaste.com，竞品）、venue-seats（开源底座）、bts（开源视角算法）、concert-venue-seat-preview（开源前端骨架）、Seat-Buddy（开源遮挡估算）
> - 核心原则：**几何可算就用代码、数据能查就用工具、位置实在不确定就明确标「示意」**。三者不许混用，尤其禁止让模型编造坐标、排距、遮挡结论。

---

## 1. 设计前提（先读，决定后面所有取舍）

### 1.1 核心问题：每个场馆的座位都不一样

体育场、体育馆、剧院的排数、分区命名、是否四面台各不相同；同一个场馆在不同演出里，舞台朝向、开放分区还会变（鸟巢某场开 A 区、另一场全内场）。

**结论：数据是「场馆 × 演出」两层，不是一份静态座位表。**

- `venue`（场馆）：几何外壳、看台环、容量、别名。建一次，长期复用。
- `show`（演出）：某场开放了哪些分区、票价档位、舞台朝向（中央四面台 / 一端主舞台 / T 台延伸）。每场一份，覆盖场馆默认值。

### 1.2 不做通用 3D 建模

本系统的目标是「让用户大致判断方向和距离感」，不是「1:1 测绘复刻现场」。竞品视野 Live 的 3D 也是粗糙示意模型（看台壳 + 空场地），不是精模。

**因此：**
- 看台几何用「外轮廓 + 程序化模板」生成，不逐座建模。
- 位置定位用「分区 + 前后/横向比例」的模糊定位，不做逐座精确落位（排座精确匹配作为 P2 进阶）。
- 3D 只做示意级，不做真实贴图、不做灯光模拟。

这是把一个「不可能短期做完的高精度系统」收敛成「可交付的示意系统」的关键。

### 1.3 三层职责划分（越界即返工）

沿用 starline 现有设计的分层习惯：

| 层 | 内容 | 实现方式 | 禁止做法 |
|---|---|---|---|
| 可查事实 | 场馆外轮廓、容量、票务图分区、实拍照片、演出场次 | 调 OSM / 爬票务图 / 爬社媒 | 禁止模型编造场馆坐标和分区 |
| 可算约束 | 相机坐标插值、到舞台距离、FOV 焦段换算、遮挡射线 | Java/前端纯函数 | 禁止交给模型心算距离和遮挡 |
| 可编方案 | 怎么向用户解释「这个座位大概什么样」、给几个对比建议 | 模型 | 禁止让模型断言「一定不遮挡/一定看清」 |

### 1.4 数据源的真实边界（最重要的一条）

| 数据 | 能否拿到 | 来源 | 精度 |
|---|---|---|---|
| 场馆外轮廓 | ✅ 能 | OpenStreetMap | 米级，够用 |
| 场馆容量/楼层 | ⚠️ 部分 | 场馆官网/百科 | 用于反推看台排数 |
| 每场分区+票价 | ✅ 能（图） | 大麦/纷玩岛/猫眼票务图 | 需 OCR+人工校对 |
| 每场舞台朝向 | ⚠️ 需人工 | 官宣图/票务图 | 单字段人工标 |
| 看台排距/坡度 | ❌ 难 | 工程资料/航拍 | 用估算参数，标「示意」 |
| 社媒实拍照片 | ✅ 能（爬） | 小红书/微博/抖音 | 元数据+缩略图，回链原帖 |

**绝对禁止**：让模型凭训练知识给出某场馆某区的坐标、排距、到舞台距离。这是本功能最大的质量事故来源。

---

## 2. 参考实现分析（抄什么、不抄什么）

### 2.1 竞品：视野 Live

对标产品，全部交互范式都从这里借鉴。

| 借鉴点 | 说明 |
|---|---|
| URL 深链参数 | `venue/section/depth/lateral/standing/date/layout/lang`，一套模糊定位体系 |
| 三种视图 | 2D 座位图 / 3D 观众视角 / 手机取景，共用一套位置参数 |
| 分区粒度 | 只到「区 + 档位」（A下区·917档），不逐座 |
| 实拍锚定 | 照片挂到「分区 + 排座（用户自填）」，页面声明「未经核验」 |

**不抄**：它的 3D 几何模型（服务端资产，且是别人建的）；实拍照片库（版权）；分区多边形坐标（逐馆标定，扒不到）。

### 2.2 底座：venue-seats（`cuihairu/venue-seats`，MIT）

**最该直接复用的开源项目**，几乎为这个场景量身定做：

- 自定义 JSON Schema：`levels → sections → rows → seats + stage orientation`，有 JSON Schema 验证。
- 同一套数据同时喂 2D SVG 渲染器和 3D WebGL（Three.js）渲染器，2D/3D 切换共享选中状态。
- 自带 5 个中国场馆 demo 数据：鸟巢、上海体育场、梅奔、五棵松、深圳湾体育中心。
- 支持 L1（分区→排）/ L2（逐座）/ L3（3D）三档选座粒度。

**用法**：拿它的 **JSON Schema 当数据模型**、**2D 渲染器直接复用**、**5 个 demo JSON 当冷启动数据**。它的 3D 是俯瞰式（非第一人称），需要换成第一人称视角。

### 2.3 视角算法：bts（`jn1xia/bts`，MIT）

单馆单场的 3D 座位视角模拟器，但视角算法价值极高：

- 第一人称相机：任意座位、眼高度、坐姿/站姿切换、拖动环顾、缩放。
- raycast 遮挡：算「能看到多少主屏幕、屏幕看起来多大」。
- 360° 四面台建模：与 `layout=center` 场景完全一致。
- 手机取景：算「舞台/人物在手机画面里多大」。

**用法**：移植它的 **相机定位、raycast 遮挡、屏幕占比估算** 三段算法到 venue-seats 底座上。它 MIT，移植合法。注意它是单文件硬编码，无数据抽象，只能当算法参考不能当框架。

### 2.4 前端骨架：concert-venue-seat-preview（MIT）

React 18 + TS + Three.js + R3F + Zustand + Vite，与 starline `frontend/` 栈一致。有「3D 总览 → 点座位 → 第一人称预览 → 购物车 → Stripe」完整流程。

**用法**：借前端组件结构和选座交互；若未来做「选座购票闭环」，借 Stripe 集成模式。其座位/价格数据写死在 env，无真实数据，不直接复用。

### 2.5 遮挡估算：Seat-Buddy（MIT）

专注「前排高个子挡住我」：考虑身高、人群密度、距离估算视线遮挡。

**用法**：借它的「身高→视线遮挡」几何公式，补进 P1 的遮挡计算，让「会不会被前排挡住」更准。

### 2.6 结论：组合方案

```
底座   = venue-seats（JSON Schema + 2D/3D 渲染器 + 5 馆数据）
视角   = 移植 bts 的相机定位 + raycast 遮挡 + 屏幕占比
遮挡   = 借 Seat-Buddy 的身高遮挡几何
支付   = 参考 concert-preview 的 Stripe 流程（可选）
实拍   = 全自建（无开源项目覆盖这一层）
```

**关键判断**：五个参考项目都不碰「社媒实拍聚合 + 座位锚定」这一层——这是竞品的护城河，必须自建。

---

## 3. 总体架构

### 3.1 四层结构

| 层 | 职责 | 技术 |
|---|---|---|
| 数据层 | 场馆几何、分区票价、演出配置、实拍元数据 | PostgreSQL + OSS（模型/图） |
| 匹配层 | 分区→坐标、depth/lateral 插值、排座换算 | Java/Spring 服务 |
| 呈现层 | 2D 座位图、3D 观众视角、手机取景 | React + Three.js |
| 社区层 | 社媒实拍采集、座位锚定、审核 | Python 爬虫 + 向量检索 |

### 3.2 与 starline 的关系

座位视角系统是**独立部署的前端 + 独立数据服务**，通过 API 对接 starline：

- starline 的 `search_artist_events` 已能拿到「演出名/场馆/时间/坐标」，作为本系统 `show` 表的冷启动种子。
- starline 用户查演出后，可跳转本系统看座位视角（深链带 `venue + show + section`）。
- 本系统不回写 starline，避免污染现有数据模型。首期只需 starline 提供一个「演出→场馆 ID」的映射接口。

### 3.3 数据流

```
OSM 外轮廓 ─┐
票务图 OCR ─┼─→ 数据层（venue/show/section）─→ 匹配层（坐标插值）─→ 呈现层（2D/3D/取景）
社媒爬虫 ───┘                                                    ↑
                                                         实拍元数据 + 锚定关系
```

---

## 4. 数据模型

数据库沿用 starline 的 PostgreSQL。核心五表 + 一个几何 JSON。

### 4.1 表结构

```sql
-- 场馆：几何外壳 + 基础属性
CREATE TABLE venue (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT NOT NULL,               -- 贵阳奥林匹克体育中心体育场
    city          TEXT,
    country       TEXT,
    capacity      INT,                         -- 容量，用于反推排数
    outline_geojson JSONB,                     -- OSM 外轮廓多边形（GeoJSON）
    geometry_gltf TEXT,                        -- 程序化生成的看台壳模型 OSS 路径（P1）
    status        SMALLINT DEFAULT 1,          -- 1 草稿 2 已审核 3 下架
    created_at    TIMESTAMPTZ DEFAULT now(),
    updated_at    TIMESTAMPTZ DEFAULT now()
);

-- 场馆别名：分区/场馆在不同平台叫法不同
CREATE TABLE venue_alias (
    id        BIGSERIAL PRIMARY KEY,
    venue_id  BIGINT REFERENCES venue(id),
    alias     TEXT,                            -- 「贵阳奥体」「金阳奥体」等
    source    TEXT                             -- 别名来源：官方/票务/用户
);

-- 演出：某场馆的一场，覆盖场馆默认布局
CREATE TABLE show (
    id           BIGSERIAL PRIMARY KEY,
    venue_id     BIGINT REFERENCES venue(id),
    name         TEXT,                         -- 薛之谦「万兽之王」巡回演唱会·贵阳站
    start_at     TIMESTAMPTZ,                  -- 2026-10-24 19:00
    stage_layout SMALLINT,                     -- 0 未建 1 中央四面台 2 一端主舞台 3 T台延伸
    stage_geom   JSONB,                        -- 舞台位置/朝向（P1）
    status       SMALLINT DEFAULT 1
);

-- 分区：一场的开放分区 + 票价档位（演出级，非场馆级）
CREATE TABLE section (
    id           BIGSERIAL PRIMARY KEY,
    show_id      BIGINT REFERENCES show(id),
    code         TEXT,                         -- 内部稳定 ID，如 a-lower-917
    label        TEXT,                         -- 展示名「A下区·917档」
    level        SMALLINT,                     -- 1 内场 2 下层 3 上层
    price_tier   INT,                          -- 票档价格（元），「非余票信息」
    polygon      JSONB,                        -- 分区在 2D 底图上的轮廓（锚多边形）
    row_count    INT,                          -- 排数（估算，P2）
    row_pitch    DOUBLE PRECISION,             -- 排距（米，估算）
    has_photo    BOOLEAN DEFAULT FALSE
);

-- 实拍：社媒爬取或用户上传的照片，锚定到分区
CREATE TABLE photo (
    id           BIGSERIAL PRIMARY KEY,
    venue_id     BIGINT REFERENCES venue(id),
    show_id      BIGINT REFERENCES show(id),
    section_id   BIGINT REFERENCES section(id),
    row_label    TEXT,                         -- 用户自填排号，如「D34排」
    seat_label   TEXT,                         -- 用户自填座号，如「63座」
    orientation  TEXT,                         -- 方位档：下层·方位7（示意）
    source_url   TEXT,                         -- 原帖链接（回链）
    thumb_url    TEXT,                         -- 缩略图
    device       TEXT,                         -- 拍摄设备
    focal_mm     DOUBLE PRECISION,             -- 焦距（EXIF，选填）
    taken_at     DATE,
    confidence   SMALLINT,                     -- 锚定置信度 0-100，低置信进「待标」池
    status       SMALLINT DEFAULT 1            -- 1 待审 2 通过 3 驳回
);

CREATE INDEX idx_photo_section ON photo(section_id);
CREATE INDEX idx_section_show ON section(show_id);
```

### 4.2 几何 JSON 格式（对齐 venue-seats）

分区几何不强依赖表结构，核心在 `section.polygon`。参考 venue-seats 的 schema，`polygon` 统一为「2D 底图上的顶点环」，`stage_geom` 为「舞台中心 + 朝向角」。**首期用简化版**，不照搬 venue-seats 完整 schema（它有逐座 seats 数组，首期用不到）：

```json
// section.polygon（2D 底图坐标，单位：米，以场地中心为原点）
{
  "ring": [[-30, -18], [30, -18], [30, 18], [-30, 18]],
  "front_edge": "bottom",          // 朝向舞台的一边，用于 depth 插值
  "stage_bearing_deg": 0           // 分区几何朝向，0=正对舞台中心
}
```

**关键取舍**：首期不做逐座 `seats` 数组，只做到「分区 + 前后/横向比例」。这是与 venue-seats 最大的差异，也是把工作量砍掉一半的关键。

---

## 5. 场馆几何获取（数据层第一块）

### 5.1 外轮廓：OpenStreetMap

用 Overpass API 查 `leisure=stadium` / `building=sports_centre`，拿到外轮廓多边形（GeoJSON），存 `venue.outline_geojson`。免费、米级精度。

### 5.2 看台壳：程序化生成

拿到外轮廓后，按容量反推看台排数与坡度，套用「体育场 / 体育馆」两种模板生成看台环 mesh，存成 glTF（P1 用）。

```
外轮廓多边形 → 内缩出内场 → 外扩出看台环 → 按 capacity 算排数 → 按排距拔高成台阶 → 导出 glTF
```

### 5.3 精修馆

热门馆（鸟巢、梅奔、深圳大运、红馆、KSPO）单独手工建模一次。首期只精修 5 个，其余走模板。

### 5.4 第一期交付

先做「平躺俯视图 + 拔起的看台壳」，不追真实贴图。产出：`venue` 表填充 5 个精修馆 + 若干模板馆。

---

## 6. 分区与票价识别（数据层第二块）

### 6.1 票务图 → 结构化

大麦/纷玩岛/猫眼每场上架时有一张分区平面图（PNG/PDF）。流程：

```
爬票务图 → 视觉模型识别「区号 + 票价 + 连通域」→ 输出 JSON → 人工后台校对 → 存 section 表
```

视觉模型（Qwen-VL / GPT-4V）的识别 prompt 模板（P0 交付，人工校对用）：

```
请识别这张演唱会场馆分区图，输出 JSON 数组：
[{"code":"A1","price":917,"polygon_approx":"x,y;x,y;..."}]
只识别图上的分区编号和票价数字，不要臆造图上没有的内容。
分区颜色仅用于区分连通域，不要输出颜色。
```

**坑**：票务图是位图，区边界靠「颜色填充连通域」提取，命名靠 OCR+人工校对。同一馆不同演出，舞台朝向会旋转、分区命名可能变。所以**分区是演出级数据**。

### 6.2 舞台配置

单字段人工标 `stage_layout`（中央四面台 / 一端主舞台 / T 台延伸），看官宣图判断。P1 的相机朝向跟着这个字段走。

### 6.3 半自动冷启动

先「半自动」——爬图后视觉模型出 JSON，人工后台校对存库。积累几十场后再训分区识别小模型（P3，可选）。

---

## 7. 位置匹配（匹配层）

### 7.1 模糊定位参数体系（对齐视野 Live）

不逐座，用三个参数表达「在分区里的大概位置」：

| 参数 | 语义 | 范围 |
|---|---|---|
| `depth` | 分区前后位置 | 0=最靠前（近舞台）→ 100=最靠后 |
| `lateral` | 分区横向位置 | 0=一侧 → 50=正中 → 100=另一侧 |
| `standing` | 坐姿/站姿 | 0 坐姿（眼高约 1.2m）/ 1 站姿（约 1.6m） |

URL 深链示例：

```
/seat?venue=guiyang-olympic&show=20261024&section=a-lower-917&depth=40&lateral=50&standing=0
```

### 7.2 相机坐标插值（可算约束，纯函数）

```
相机位置 = section.polygon 内沿 front_edge 方向、横向中心线的 (depth, lateral) 插值点
相机高度 = standing ? 1.6 : 1.2（米，相对该排地面）
相机朝向 = 对准 stage_geom 的中心（若 stage_layout=center 则对准内场中心）
```

这是从 bts 移植的核心逻辑。`depth`/`lateral` 的插值用线性插值，落在分区锚多边形内部，避免穿模。

### 7.3 排座精确匹配（P2 进阶）

用户填了排号/座号时，用 `section.row_pitch` 把排号换算成 depth；没填就用滑块。P2 才做，P0/P1 只做滑块。

---

## 8. 3D 呈现与手机取景（呈现层）

### 8.1 2D 座位图（P0）

复用 venue-seats 的 2D SVG 渲染器：分区高亮、票价档位色、sold-out 状态、hover 提示。输入是 `section.polygon` 集合。

### 8.2 3D 观众视角（P1）

Three.js + React Three Fiber，第一人称相机（移植 bts）：

- 相机摆在 7.2 算出的位置，用户拖动环顾、缩放。
- raycast 遮挡：从眼睛向舞台/主屏发射线，算「能看到舞台的百分比」和「舞台在视野里的张角（看起来多大）」。
- 身高遮挡：借 Seat-Buddy 公式，估算「前排人群是否挡住舞台下沿」。

**只做示意级**：看台壳 + 舞台箱体 + 内场平面，不建真实舞台细节、不做真实贴图。

### 8.3 手机取景（P1）

算「舞台在手机画面里多大」：

```
FOV = 2 * atan(传感器半宽 / 焦距)     # 焦距来自焦段选择 1x/2x/5x/8x/10x
画面占比 = 舞台在画面中的投影大小 / 画面尺寸
```

支持竖拍 3:4 / 横拍 4:3 切换、导出取景图。这是把「看起来多大」从直觉变成可量化数值的关键功能。

---

## 9. 社媒实拍聚合（社区层，全自建）

### 9.1 采集端

| 项 | 方案 |
|---|---|
| 数据源优先级 | 小红书 > 微博 > 抖音图文 > 大众点评（按「座位信息密度」排序） |
| 监听关键词 | `{场馆名/别名} ∩ {演出名} ∩ {区/排/座/视角/实拍}` |
| 抓取方式 | 各平台开放搜索 API 或 RSS 聚合，避免直连反爬；每平台每分钟个位数请求，走代理池+降速 |
| 存储 | 只存元数据 + 缩略图，原图回链原帖 |

### 9.2 座位锚定（最难的一步，三档由易到难）

| 档 | 方法 | 精度 | 命中率 |
|---|---|---|---|
| 1 | 正文正则抽取「X区X排X座」 | 到排座 | 30-40%，最准 |
| 2 | 视觉模型反推方位（下层/上层、正对/侧位、近端/远端） | 到方位 | 60%+ |
| 3 | 图像特征向量与已标样本相似检索 | 到方位 | 进阶，需先积累样本 |

档位 3 用 CLIP 向量 + 向量库（Milvus/Qdrant），每个馆积累几十张已标样本后才有用。

### 9.3 审核与免责

机器给候选 `(section, row, seat, confidence)`，人工后台确认。低置信进「待标」池。页面必须声明：「位置由用户填写，未经核验；同区不等于同座」（对齐视野 Live）。

### 9.4 合规红线

只存元数据+缩略图+回链，不存原图、不二次分发；遵守各平台 robots 与频控。这条不图省事，否则内容层有下架风险。

---

## 10. 技术选型（贴合 starline 现有栈）

| 模块 | 选型 | 理由 |
|---|---|---|
| 后端 API | Java/Spring Boot（新增独立服务） | 复用 starline 体系 |
| 数据库 | PostgreSQL + PostGIS（外轮廓） | 复用 starline 数据库 |
| 3D 渲染 | 前端 Three.js + React Three Fiber | 复用 frontend/ 现有栈 |
| 2D 渲染 | 移植 venue-seats SVG 渲染器 | MIT，省前端开发 |
| 几何存储 | glTF + OSS | 模型走 OSS，结构化坐标走库 |
| 票务图识别 | Qwen-VL / GPT-4V 出 JSON + 人工校对 | 半自动冷启动 |
| 爬虫 | Python（playwright + httpx）独立服务 | 与 Java 主解耦，MQ 回写 |
| 消息总线 | Redis Stream 或 RabbitMQ | 爬虫→解析→锚定→入库异步链 |
| 图像特征 | CLIP 向量 + Milvus/Qdrant | P3 视角图检索 |
| 缓存 | Redis | 热门馆配置与实拍列表 |

---

## 11. 分阶段实施路线

### Phase 0：2D 分区图 + 票价档位（2-3 周，可独立上线）

- venue-seats 底座跑通，5 个 demo JSON 入库。
- OSM 拉 5 个高频馆外轮廓。
- 爬 5 馆票务图，视觉模型出 JSON，人工校对，填 `venue/show/section`。
- 交付 2D 俯视分区图 + 票价档位 + depth/lateral 滑块选位置。
- **验收**：用户能选区、看票价、看大致方位。

### Phase 1：3D 观众视角 + 手机取景（3-4 周）

- 程序化生成看台壳 glTF，接 Three.js 第一人称相机。
- 移植 bts 相机定位 + raycast 遮挡；借 Seat-Buddy 身高遮挡。
- 手机取景焦段换算。
- **验收**：用户能坐进 3D 看视角、看遮挡、看手机画面占比。

### Phase 2：社媒实拍聚合（持续）

- 先做正文规则抽取（档位 1，最准最便宜）。
- 跑通小红书监听 → 落库 → 按分区展示。
- 档位 2/3 作为后续迭代。
- **验收**：某分区能展示多条带座位信息的实拍。

### Phase 3（可选，不承诺）：分区识别小模型、图像检索匹配、选座购票闭环

---

## 12. 三个最易踩的坑

1. **分区命名不稳定**：同一馆不同演出，主办方可能换命名（A区/1区/东区）。用内部 `section.code` + `venue_alias` 别名表，别硬绑显示名。
2. **舞台朝向会旋转**：`stage_layout` 三种 + 朝向角是演出级字段，相机朝向跟着变，不能写死。
3. **爬虫合规**：只存元数据+缩略图+回链，遵守 robots 和频控，否则内容层下架风险。
