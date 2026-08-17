import express from "express";
import crypto from "crypto";

const app = express();
app.use(express.json());

const instructions = `
You are Coach, a concise voice-first strength-training companion used during an active gym session.
The deterministic workout engine on the phone is authoritative. Use a function tool for EVERY workout state change.
Never invent logged reps, load, RPE, exercise history, timer state, or progression.
If the user says a number after you asked for RPE, interpret it as RPE and call log_rpe.
If the user says they completed N reps, call log_reps. If they say both reps and RPE, call log_reps first, then log_rpe if the returned state requests RPE.
Keep spoken replies brief: usually one or two sentences.
Do not chatter during rest. If the user reports pain, call report_pain and never encourage pushing through pain.
When a tool returns engine_message, use it as the factual basis of your reply rather than contradicting it.
`;

const tools = [
  { type: "function", name: "get_workout_state", description: "Read current exercise, set, load, rep target, rest and logged sets.", parameters: { type: "object", properties: {}, additionalProperties: false } },
  { type: "function", name: "start_set", description: "Start the currently prescribed set when the user says ready or starting.", parameters: { type: "object", properties: {}, additionalProperties: false } },
  { type: "function", name: "log_reps", description: "Record the number of completed reps for the active set.", parameters: { type: "object", properties: { reps: { type: "integer", minimum: 0, maximum: 100 } }, required: ["reps"], additionalProperties: false } },
  { type: "function", name: "log_rpe", description: "Record RPE for the set after reps have been logged.", parameters: { type: "object", properties: { rpe: { type: "number", minimum: 1, maximum: 10 } }, required: ["rpe"], additionalProperties: false } },
  { type: "function", name: "skip_rest", description: "End the current rest period early.", parameters: { type: "object", properties: {}, additionalProperties: false } },
  { type: "function", name: "whats_next", description: "Read what the athlete should do next.", parameters: { type: "object", properties: {}, additionalProperties: false } },
  { type: "function", name: "pause_workout", description: "Pause the workout.", parameters: { type: "object", properties: {}, additionalProperties: false } },
  { type: "function", name: "resume_workout", description: "Resume a paused workout.", parameters: { type: "object", properties: {}, additionalProperties: false } },
  { type: "function", name: "end_workout", description: "End the workout only when the user clearly requests it.", parameters: { type: "object", properties: {}, additionalProperties: false } },
  { type: "function", name: "report_pain", description: "Pause training and enter the pain-safety flow when the user reports pain.", parameters: { type: "object", properties: { area: { type: "string" }, details: { type: "string" } }, required: ["area"], additionalProperties: false } },
];

app.get("/health", (_req, res) => res.json({ ok: true }));

app.get("/token", async (req, res) => {
  if (!process.env.OPENAI_API_KEY) return res.status(500).json({ error: "OPENAI_API_KEY is not set" });
  const userSeed = req.get("X-Coach-User") || "local-dev-user";
  const safetyId = crypto.createHash("sha256").update(userSeed).digest("hex");
  const body = {
    session: {
      type: "realtime",
      model: process.env.OPENAI_REALTIME_MODEL || "gpt-realtime-2.1",
      instructions,
      output_modalities: ["audio"],
      audio: {
        input: {
          format: { type: "audio/pcm", rate: 24000 },
          turn_detection: { type: "semantic_vad" },
        },
        output: {
          format: { type: "audio/pcm" },
          voice: process.env.OPENAI_REALTIME_VOICE || "cedar",
        },
      },
      tools,
      tool_choice: "auto",
    },
  };

  try {
    const response = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${process.env.OPENAI_API_KEY}`,
        "Content-Type": "application/json",
        "OpenAI-Safety-Identifier": safetyId,
      },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    res.status(response.status).type("application/json").send(text);
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: "Failed to create Realtime client secret" });
  }
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`Coach Realtime token server listening on :${port}`));
