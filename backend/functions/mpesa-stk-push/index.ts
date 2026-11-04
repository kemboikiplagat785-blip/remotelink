// Supabase Edge Function: mpesa-stk-push
// Initiates an M-Pesa STK (Lipa na M-Pesa Online) push for a subscription payment,
// records a pending transaction, and returns the CheckoutRequestID to the app.
// Deploy: supabase functions deploy mpesa-stk-push
// Secrets: supabase secrets set MPESA_CONSUMER_KEY=... MPESA_CONSUMER_SECRET=... \
//          MPESA_SHORTCODE=... MPESA_PASSKEY=... MPESA_CALLBACK_URL=... MPESA_ENV=sandbox //          SUBSCRIPTION_PRICE_KES=250
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ENV = Deno.env.get("MPESA_ENV") ?? "sandbox";
const BASE = ENV === "production"
  ? "https://api.safaricom.co.ke"
  : "https://sandbox.safaricom.co.ke";
const KEY = Deno.env.get("MPESA_CONSUMER_KEY")!;
const SECRET = Deno.env.get("MPESA_CONSUMER_SECRET")!;
const SHORTCODE = Deno.env.get("MPESA_SHORTCODE")!;
const PASSKEY = Deno.env.get("MPESA_PASSKEY")!;
const CALLBACK = Deno.env.get("MPESA_CALLBACK_URL")!;
// Set here rather than taken from the request, so a client can't pay KES 1 for a month.
const PRICE_KES = Number(Deno.env.get("SUBSCRIPTION_PRICE_KES") ?? "250");

serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) return json({ error: "unauthorized" }, 401);

  const { phone } = await req.json().catch(() => ({}));
  if (!phone) return json({ error: "missing 'phone'" }, 400);
  const msisdn = normalizeMsisdn(phone);

  // 1. OAuth token
  const tokenRes = await fetch(`${BASE}/oauth/v1/generate?grant_type=client_credentials`, {
    headers: { Authorization: "Basic " + btoa(`${KEY}:${SECRET}`) },
  });
  const { access_token } = await tokenRes.json();
  if (!access_token) return json({ error: "mpesa auth failed" }, 502);

  // 2. STK push
  const ts = timestamp();
  const password = btoa(`${SHORTCODE}${PASSKEY}${ts}`);
  const stkRes = await fetch(`${BASE}/mpesa/stkpush/v1/processrequest`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${access_token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      BusinessShortCode: SHORTCODE,
      Password: password,
      Timestamp: ts,
      TransactionType: "CustomerPayBillOnline",
      Amount: PRICE_KES,
      PartyA: msisdn,
      PartyB: SHORTCODE,
      PhoneNumber: msisdn,
      CallBackURL: CALLBACK,
      AccountReference: "RemoteSupport",
      TransactionDesc: "Subscription",
    }),
  });
  const stk = await stkRes.json();

  // 3. Record a pending transaction (service-role write, bypasses RLS).
  const admin = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );
  await admin.from("mpesa_transactions").insert({
    owner_id: user.id,
    phone_number: msisdn,
    amount: PRICE_KES,
    checkout_request_id: stk.CheckoutRequestID ?? null,
    status: "pending",
  });

  return json({ ok: stkRes.ok, checkoutRequestId: stk.CheckoutRequestID, raw: stk },
    stkRes.ok ? 200 : 502);
});

function normalizeMsisdn(p: string): string {
  const d = p.replace(/\D/g, "");
  if (d.startsWith("0")) return "254" + d.slice(1);
  if (d.startsWith("7") || d.startsWith("1")) return "254" + d;
  return d;
}
function timestamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}
function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });
}
