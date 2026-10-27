# Owner phone acceptance checklist

Automated builds, Room/HTTPS tests and emulator tests do not establish acceptance
on your physical phone. All items below remain **not run on the owner's phone**.
Use only made-up notes, conversations, photos and recordings for these checks.

1. **Update in place:** use the accepted APK signed with the same original key.
   Choose Update, never uninstall or clear storage. If Android refuses the signer,
   stop and retain the installed app. Confirm existing chats, drafts, pairing and
   privacy/unlock preferences remain. CI debug APKs are test artifacts, not an
   update for a differently signed installation.
2. **Connection:** keep the PC awake, Private AI running/unlocked and background
   work resumed. Check Connected on private LAN; repeat over the already configured
   private VPN. Disable/re-enable phone Wi-Fi and briefly stop/restart the desktop.
   Check disconnected/last-sync status is honest and reconnection needs no new key.
3. **Lock:** lock the phone during an unsent synthetic chat, then unlock normally.
   Content disappears while locked; the encrypted draft returns after authentication.
   Confirm your chosen foreground timeout does not override the phone's system lock.
4. **Chat receipt:** ask a synthetic question, synchronize, close/reopen the phone
   app, and use **Ask again · new request** on the answer. It must repeat the original
   question as a new request and retain its original minimum privacy requirements.
   This linkage needs the updated desktop's optional message IDs. Older desktops
   still chat/sync; unrelated history must not acquire Ask again through matching text.
5. **Camera and microphone:** take a synthetic evidence photo and a short recording.
   Deny each OS permission once and confirm a clear error; then grant it and retry.
   Cancel should release the device. Do not record other people without permission.
6. **Notifications:** with the separately granted restricted notification permission,
   create a synthetic due reminder and lock the phone. Confirm only generic text is
   visible. Test battery restrictions/background delivery; do not claim it succeeded
   merely because a foreground notification worked.
7. **Offline drafts:** disconnect, type a note/chat and attach synthetic evidence;
   close/reopen and authenticate. Confirm auto-saved content remains, then reconnect.
   Drafts must not send themselves. A queued explicit Send must retain its identity
   and clearly report pending/review states rather than duplicate uncertain work.
8. **Meeting:** record and finish a short synthetic meeting on the phone. Reconnect
   and confirm the PC receives it and performs local transcription/summary processing.
   If the offline speech runtime is unavailable, retain the audio and show setup
   guidance; do not invent a transcript. Verify a follow-up remains reviewable.

Record APK/source version, phone/Android version, desktop version, result and any
reproduction steps. Never include pairing links, credentials, PHI or real screenshots
in a public issue. Leave a failing checklist item marked failed or untested.
