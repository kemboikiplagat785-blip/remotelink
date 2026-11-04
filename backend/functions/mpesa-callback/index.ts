// Supabase Edge Function: mpesa-callback
// Safaricom calls this URL (MPESA_CALLBACK_URL) with the STK push result.
// It must be deployed with --no-verify-jwt because Safaricom cannot send a JWT.
// Deploy: supabase functions deploy mpesa-callback --no-verify-jwt
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

serve(async (req) => {
  const body = await req.json().catch(() => null);
  const cb = body?.Body?.stkCallback;
  if (!cb) return ok(); // Always 200 so Safaricom stops retrying.

  const checkoutId = cb.CheckoutRequestID as string;
  const resultCode = cb.ResultCode as number;
  const resultDesc = cb.ResultDesc as string;
  const status = resultCode === 0 ? "success" : "failed";

  const admin = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  const { data: txn } = await admin.from("mpesa_transactions")
    .update({ status, result_desc: resultDesc })
    .eq("checkout_request_id", checkoutId)
    .select("owner_id")
    .maybeSingle();

  // On success, extend the subscription by 30 days.
  if (status === "success" && txn?.owner_id) {
    const periodEnd = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
    await admin.from("subscriptions")
      .update({ status: "active", current_period_end: periodEnd, updated_at: new Date().toISOString() })
      .eq("owner_id", txn.owner_id);
  }
  return ok();
});

function ok(): Response {
  return new Response(JSON.stringify({ ResultCode: 0, ResultDesc: "Accepted" }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
