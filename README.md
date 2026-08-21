# CrowdShield AI – AI-Powered Crowd Congestion & Early Warning System

> **24-Hour Hackathon Prototype (MVP)**  
> An end-to-end computer vision and spatial analytics platform designed to analyze crowd video feeds in real time, estimate density per spatial zone, calculate congestion risk, and trigger early warning alerts on an interactive web dashboard.

---

## 🌟 Key Features

- **Automated AI Person Detection**: Powered by **YOLOv8** (with OpenCV HOG / synthetic simulation fallbacks for guaranteed 100% uptime in any environment).
- **Spatial Zone Partitioning**: Multi-zone bounding and Point-in-Polygon centroid mapping (e.g., Zone A Entrance, Zone B Concourse, Zone C Exit).
- **Dynamic Density & Capacity Analytics**: Computes real-time crowd density ($N_z / \text{Area}_z$) and capacity utilization percentages per zone.
- **Multi-Level Risk Engine**: Rule-based state machine and rate-of-change evaluator classifying risk into `LOW`, `MEDIUM`, `HIGH`, and `CRITICAL`.
- **Priority Queue Alert Feed**: Prioritizes severe congestion events using min-heap queue structures with sliding-window deduplication.
- **Glassmorphic Command Center Dashboard**: Responsive dark mode UI featuring live MJPEG computer vision stream, live counters, zone status cards, alert feed, and dynamic Chart.js trend graphs.
- **Video Upload Support**: Supports `.mp4`, `.avi`, `.mov`, `.mkv`, and `.webm` video files for offline analytics.

---

## 🏗️ System Architecture

```text
Prerecorded Video / Synthetic Stream
       │
       ▼
Flask Backend (app.py)
       │
       ▼
Video Processing (OpenCV)
       │
       ▼
Person Detection Module (YOLOv8 / detector.py)
       │
       ▼
Spatial Zone Assignment (density.py)
       │
       ▼
Density & Capacity Calculation Engine
       │
       ▼
Risk Engine & Priority Alert Queue (risk.py)
       │
       ▼
Dashboard Telemetry API & MJPEG Stream (dashboard.py)
       │
       ▼
Web Dashboard UI (HTML5 / Glassmorphism CSS / Chart.js / main.js)
```

---

## 📁 Project Structure

```text
CrowdShield/
├── app.py                  # Main Flask web server & REST endpoints
├── detector.py             # YOLOv8 & HOG Person Detection engine
├── density.py              # Spatial Zone Assignment & Density Analyzer
├── risk.py                 # Risk Engine & Priority Queue Alert Manager
├── dashboard.py            # Stream Coordinator & Telemetry aggregator
├── utils.py                # Frame rendering, overlays & synthetic crowd stream generator
├── config.py               # Zone definitions, polygon coordinates & risk thresholds
├── requirements.txt        # Python package dependencies
├── README.md               # Project documentation
├── templates/
│   └── index.html          # Unified Command Center Dashboard template
├── static/
│   ├── css/
│   │   └── style.css       # Premium Dark Glassmorphism Stylesheet
│   └── js/
│       └── main.js         # Telemetry consumer, Chart.js & dynamic DOM controller
├── uploads/                # Video upload directory
└── models/                 # Model weights store (yolov8n.pt)
```

---

## 🚀 Quick Start Guide

### 1. Prerequisites
- Python 3.10+ installed.

### 2. Install Dependencies
Navigate to the project root directory and install required Python packages:

```bash
cd CrowdShield
pip install -r requirements.txt
```

### 3. Run Application
Launch the Flask development server:

```bash
python app.py
```

### 4. Access Dashboard
Open your browser and navigate to:
[http://127.0.0.1:5000](http://127.0.0.1:5000)

---

## 🔌 API Endpoints Reference

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/` | `GET` | Renders the main web dashboard interface. |
| `/video_feed` | `GET` | Streams annotated computer vision frames as MJPEG stream. |
| `/upload` | `POST` | Accepts video file upload (`video_file` multipart form). |
| `/api/telemetry` | `GET` | Returns current JSON metrics (total count, risk level, zone stats, alerts, trends). |
| `/api/reset` | `POST` | Resets stream back to synthetic demo crowd stream. |
| `/health` | `GET` | Health check endpoint. |

---

## 🔬 Core Algorithms

1. **Person Detection**: YOLOv8 anchor-free box detection + NMS ($\text{IoU} = 0.45$, $\text{Conf} \ge 0.35$).
2. **Zone Assignment**: Point-in-polygon ray casting (`cv2.pointPolygonTest`).
3. **Density Smoothing**: Exponential Moving Average $\bar{D}^{(t)} = \alpha D^{(t)} + (1-\alpha) \bar{D}^{(t-1)}$ ($\alpha = 0.35$).
4. **Risk Classification**: State machine mapping capacity utilization ($<35\%$ `LOW`, $35-65\%$ `MEDIUM`, $65-85\%$ `HIGH`, $\ge 85\%$ `CRITICAL`).
5. **Alert Deduplication**: Priority queue min-heap ranking severity (`CRITICAL: 1` > `HIGH: 2`) with a 4-second cooldown window per zone.

---

## 🔮 Future Enhancements

- Live RTSP/CCTV IP Camera stream ingestion.
- Optical Flow directional velocity vector estimation to predict stampede motion.
- Automated 2D density heatmap matrix rendering.
- Multi-camera person re-identification (Re-ID).
