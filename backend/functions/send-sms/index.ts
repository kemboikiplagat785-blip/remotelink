// Supabase Edge Function: send-sms
// Sends an SMS via a provider (Africa's Talking shown; swap for Twilio if you prefer).
// Deploy: supabase functions deploy send-sms
// Secrets: supabase secrets set AT_USERNAME=... AT_API_KEY=... AT_SENDER_ID=...
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const AT_USERNAME = Deno.env.get("AT_USERNAME") ?? "";
const AT_API_KEY = Deno.env.get("AT_API_KEY") ?? "";
const AT_SENDER_ID = Deno.env.get("AT_SENDER_ID") ?? "";

serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  // Require an authenticated caller.
  const authHeader = req.headers.get("Authorization") ?? "";
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) return json({ error: "unauthorized" }, 401);

  const { to, message } = await req.json().catch(() => ({}));
  if (!to || !message) return json({ error: "missing 'to' or 'message'" }, 400);

  const body = new URLSearchParams({
    username: AT_USERNAME,
    to,
    message,
    ...(AT_SENDER_ID ? { from: AT_SENDER_ID } : {}),
  });

  const res = await fetch("https://api.africastalking.com/version1/messaging", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "Accept": "application/json",
      "apiKey": AT_API_KEY,
    },
    body,
  });
  const result = await res.json().catch(() => ({}));
  return json({ ok: res.ok, provider: result }, res.ok ? 200 : 502);
});

function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
