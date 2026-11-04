# RemoteSupport

A consent-based remote screen-view-and-control app for Android. A **controller**
device views and drives a **target** device over WebRTC, with Supabase for auth,
data, and signaling.

This project is a clean-room reimplementation modeled on the observed architecture
of an existing app. It deliberately **does not** auto-accept Android's screen-capture
consent dialog or silently resume capture after reboot: the target user explicitly
approves each capture session. Keep it thatl way — it is what makes the app lawful
to ship and compliant with Google Play policy.

## Stack

- Kotlin + Jetpack Compose (Material 3), MVVM
- WebRTC (`io.getstream:stream-webrtc-android`) — screen video + control data channel
- Supabase — Auth, Postgres (Postgrest), Realtime (signaling), Edge Functions
- Room (local device/pair cache), WorkManager (background workers)
- Subscriptions via M-Pesa STK push; SMS via an edge function

## Roles

- **Target**: captures its screen (MediaProjection), streams it, and injects
  incoming control events via an AccessibilityService. Requires the user to grant
  the accessibility service and approve screen capture each session.
- **Controller**: renders the remote video and sends control events (taps,
  swipes, text, nav) back over the WebRTC data channel.

## Setup

1. **Rename the app id**: change `appId` in `gradle.properties` from the
   placeholder `com.acme.remotesupport` to your own, then sync. Update
   `app/src/main/res/values/strings.xml` `app_name`.
2. **Secrets**: copy `secrets.defaults.properties` to `secrets.properties` and
   fill in your Supabase URL/anon key, TURN server, and (optional) M-Pesa / SMS
   values. `secrets.properties` is gitignored and never committed.
3. **Backend**: deploy `backend/schema.sql` to your Supabase project, then deploy
   the edge functions in `backend/functions/`. See `backend/README.md`.
4. **SDK**: point `local.properties` at your Android SDK (Android Studio does this
   on sync), e.g. `sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk`.
5. Build: `./gradlew :app:assembleDebug`.

## Module map

```
app/
  src/main/
    AndroidManifest.xml
    java/com/acme/remotesupport/
      data/            DTOs, repositories, Room, Supabase client
      data/webrtc/     ControlEvent protocol, RtcPeer, signaling
      service/         screen capture, control session, update
      accessibility/   gesture/input injection
      ui/              Compose screens (auth, devices, control, profile, subscription)
backend/
  schema.sql
  functions/           send-sms, mpesa-stk-push, mpesa-callback
```

## Legal

Only deploy to devices whose users know the app is installed and consent to being
viewed/controlled. Hidden monitoring is illegal in most jurisdictions and violates
Play policy.
