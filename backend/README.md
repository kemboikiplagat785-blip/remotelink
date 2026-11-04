# Backend (Supabase)

## 1. Database

Open the Supabase SQL editor and run `schema.sql`. It creates all tables, the
owner-scoped RLS policies, the `screenshots` storage bucket, realtime publication,
and a trigger that auto-creates a `profiles` row and a 14-day trial `subscriptions`
row on signup.

## 2. Edge functions

Install the Supabase CLI, then from this `backend/` directory:

```bash
supabase functions deploy send-sms
supabase functions deploy mpesa-stk-push
supabase functions deploy mpesa-callback --no-verify-jwt
```

### Secrets

```bash
# SMS (Africa's Talking example)
supabase secrets set AT_USERNAME=... AT_API_KEY=... AT_SENDER_ID=...

# M-Pesa Daraja
supabase secrets set \
  MPESA_ENV=sandbox \
  MPESA_CONSUMER_KEY=... \
  MPESA_CONSUMER_SECRET=... \
  MPESA_SHORTCODE=... \
  MPESA_PASSKEY=... \
  MPESA_CALLBACK_URL=https://YOUR-PROJECT.functions.supabase.co/mpesa-callback   SUBSCRIPTION_PRICE_KES=250
```

`SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` are injected
automatically by the platform.

## Notes

- `mpesa-callback` is public (`--no-verify-jwt`) because Safaricom cannot present
  a user JWT. It writes with the service-role key and is safe because it only
  matches an existing `checkout_request_id`.
- SMS is deliberately server-side only. The app never holds the SEND_SMS
  permission; it calls `send-sms` with the user's auth token.
