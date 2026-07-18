# Memory Integration Lab: Memory-Enabled Mobile Assistant & Optimized AI Workflow Pipeline

Welcome to the **Memory Integration Lab**! This project demonstrates an advanced, production-ready, client-side **Cognitive Retrieval-Augmented Generation (RAG)** mobile assistant. It combines secure on-device persistence via **Room Database** with the reasoning power of the **Gemini 3.5 Flash** model to establish a dynamic, self-evolving personal context cache.

---

## 🚀 Key Architectural Pillars

### 1. Secure Local Database (Room Engine)
The app operates on an offline-first memory store using a high-performance **Room SQLite Database**:
*   **Memories Table**: Stores facts, user preferences, interests, and relationship details. Supports both manual injection (users explicitly declaring facts) and autonomous extraction (AI learning on the fly).
*   **Chat History Table**: Persists user and model communication records, including the detailed internal execution steps of the AI pipeline for each message.

### 2. The 5-Stage Optimized AI Workflow Pipeline
Every message sent triggers a structured pipeline execution, which is fully tracked and visualizable in real-time within the UI:
```
[ User Input ]
      │
      ▼
 🔍 STEP 1: Memory Retrieval (RAG)
      │  ├─ Search for matches across the database
      │  └─ Inject related personal facts as context
      ▼
 ⚙️ STEP 2: Prompt Synthesis
      │  ├─ Construct specialized System Instructions
      │  └─ Wrap conversation history + active memories
      ▼
 🤖 STEP 3: Gemini API Inference
      │  ├─ Run gemini-3.5-flash model
      │  └─ Measure execution latency (ms)
      ▼
 🧠 STEP 4: Background Fact Extraction
      │  ├─ Auto-evaluate user input for new facts
      │  ├─ Parse structured JSON response
      │  └─ Save new details to the local Room DB
      ▼
 ✅ STEP 5: Pipeline Completion
```

### 3. Material Design 3 Fluid Workspace
The application features a modern Material 3 interface:
*   **Assistant Workspace (Tab 1)**: Interactive message thread showing dynamic status indicators. Tap on any assistant message to expand a dedicated timeline trace detailing its pipeline execution metrics and logs.
*   **Memory Vault (Tab 2)**: Full control panel of your personal data. Features manual fact injection, category-based filters (Preference, Personal, Fact, Work, General) with color-coded chips, and secure database wipes.

---

## 🛠️ Getting Started & Setup

### Gemini API Key Configuration
The application is pre-configured with the **Secrets Gradle Plugin** to load environment variables from a `.env` file and expose them safely in code through `BuildConfig.GEMINI_API_KEY`.

1.  Open the **Secrets Panel** in the Google AI Studio UI.
2.  Input your `GEMINI_API_KEY` (use the one provided, or your own secure key).
3.  The build environment will automatically sync the key into the project's `.env` configuration file at compile time.
4.  Launch the app! The status pill at the top right of the top-bar will change from red **"No API Key"** to green **"Gemini Connected"**.

> [!CAUTION]
> **Prototype Security Warning**: This prototype accesses the Gemini API key via `BuildConfig` compiled directly into the client application. Android APK files can be easily decompiled and static variables decompiled. Do not distribute the generated APK publicly with production API keys.

---

## 📂 Project Directory Structure

```
/app/src/main/java/com/example/
│
├── data/
│   └── MemoryDb.kt            # Room entities (Memory, ChatMessage), DAOs, Database, and Repository
│
├── api/
│   └── GeminiApi.kt           # Retrofit client, OkHttpClient configuration with timeouts, and request/response models
│
├── ui/
│   ├── theme/                 # Material 3 colors, typography, and styles
│   ├── screens/
│   │   └── MainScreen.kt      # Main visual Compose UI, Assistant chat workspace, and Memory Vault dashboard
│   └── MemoryViewModel.kt     # Main State engine, 5-stage pipeline runner, and extraction parser
│
└── MainActivity.kt            # App entrypoint, binds Database, Repository, and ViewModel factories
```

---

## 📊 Evaluation & Metrics
To monitor and evaluate the performance of the pipeline, the system logs:
*   **Prompt Tokens**: Size of context injected based on active memories.
*   **API Latency (ms)**: Time taken to receive response from the Gemini server.
*   **Extraction Accuracy**: Visual feedback in the form of system toast cards whenever the background pipeline learns a new preference.
