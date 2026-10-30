# Study Assistant 📚

[简体中文](../../README.md) · [繁體中文](README.zh-TW.md) · [EN](README.en.md) · [JA](README.ja.md) · [KO](README.ko.md)

![License](https://img.shields.io/github/license/ShizhenZhou/StudyAssistant)
![Release](https://img.shields.io/github/v/release/ShizhenZhou/StudyAssistant)
![Downloads](https://img.shields.io/github/downloads/ShizhenZhou/StudyAssistant/total)
![CI](https://github.com/ShizhenZhou/StudyAssistant/actions/workflows/build.yml/badge.svg)
![Platform](https://img.shields.io/badge/Android-16%2B%20(API%2036)-3DDC84?logo=android&logoColor=white)

A personal Android study assistant for photo/text questions, AI answers, a mistake notebook and review. No account system or developer-operated server.
Developed by zsz with assistance from DeepSeek and GPT. Download published builds from [GitHub Releases](https://github.com/ShizhenZhou/StudyAssistant/releases).

## Features

- 📷 Ask with text and up to 3 images; automatic/manual cropping and grading of handwritten answers.
- 🤖 Streaming answers, image follow-ups, stop/continue generation, similar exercises and KaTeX formulas.
- 🧠 AI management has API settings and usage, AI cropping, solver settings and custom instructions. Both fast and thinking modes use `deepseek-flash`, with thinking disabled/enabled respectively. AI output language is separate from interface language.
- 📚 Categorize, tag, search, sort and batch-manage notebook entries.
- 🕘 Solving history retains initial solutions, text/image questions and grading (**latest 200 entries, up to 30 days**). Follow-ups update the same entry and preserve its first time; opening Notebook/Review creates no new history.
- 🏷 Preview AI suggestions or manual tag groups before merging; undo the latest merge. Tag drafts save automatically; the latest 20 local AI-analysis diagnostics can be exported manually.
- 📖 Review at `0/1/2/4/7/15/30` days, with statistics and daily reminders.
- 🌐 Simplified/Traditional Chinese, English, Japanese and Korean interfaces; themes and global text size.
- 📦 ZIP backups (`.zip`) deduplicate images and split large archives; legacy v3 JSON import remains supported. Names use `SABak-timestamp.zip`, with `-1of3` for parts; select all parts when restoring. Local content wins for matching question IDs; categories/tags are imported only for new questions.
- 🗄️ Automatic backups use `SABak_auto-` and become eligible about 30 seconds after opening the app each day. Skip empty notebooks or days with a complete manual/automatic backup; otherwise back up daily even if unchanged. A manual request can take over a queued automatic job, which continues after leaving the page. If more than **3 complete sets** exist, retain the newest **2** before starting; retain **3** after success. Legacy automatic names are included; manual, damaged, incomplete and unrecognized files are kept. Foreground success shows an in-app notice; final background failure uses a notification when permitted.
- 💾 Retry failed saves and recover persisted copies after restart. Conflicts never overwrite existing content; pending content can be exported manually.
- 💬 Regeneration keeps the old answer until success. Interrupted conversations become local drafts recoverable from Home or data management; excluded from ordinary backups, with rescue export for assisted recovery.
- ↩️ A 3-second undo window follows deletion of questions, history or categories. Review keeps the current session's selection; AI grading needs confirmation. Daily/weekly/streak/trend statistics are estimates from latest review dates.
- ⬆️ In-app updates use Android background downloads, restore progress after navigation and offer notification progress/cancellation. Downloads continue when notifications are unavailable; Live Update presentation depends on the device and needs physical-device confirmation. Transfers attempt to resume after connectivity returns. Same-version packages are compared by UTC build time. Compatibility metadata blocks unsupported downloads; installation checks actual minimum OS, signing certificate, version and SHA-256.

## Data and privacy

Questions, history and review databases stay on the device. Necessary prompts, images, context, custom instructions, related category/tag names and parameters go directly to the **official DeepSeek API**, authenticated with your own key; there is no developer relay. Android Keystore encrypts the locally stored key.

Explicitly choosing tag organization → AI analysis sends all tag IDs, names and associated question counts, but no question text, answers or images. Updates access GitHub without question content. No analytics reporting.

Automatic backups write only to your SAF-selected folder. The app does not initiate cloud sync; a cloud drive provider may sync its folder independently. Crash logs and AI diagnostics are exported only on request to your chosen location.

## Technology

Kotlin **2.4.21** · Jetpack Compose / Material3 · MVVM · Room **2.8.5** (DB v10) · Retrofit / OkHttp (SSE) · kotlinx.serialization · CameraX **1.6.2** · WebView / KaTeX **0.19.1** · Navigation Compose · WorkManager **2.12.0** (one-time backups) · AlarmManager · Android Keystore.

AGP **9.4.0**, Gradle wrapper **9.8.2**, JDK **25**; compileSdk / targetSdk **37**, minSdk **36** (Android **16+**), build-tools **36.0.0**. Bak v4 uses native ZIP / DEFLATE and imports v3 JSON. See [DATA_SCHEMA.md](../DATA_SCHEMA.md).

## Version history

### v0.6.8.7

- 📷 Pinch to zoom in the camera and see the current zoom ratio.
- 🧠 Request a Thinking live update during answers, with the app icon and blue accent.
- 🔔 Clear download-complete alerts when opened or Install is tapped; show the completion capsule for two minutes, then retain a regular notification.
- 📌 `versionCode` **88**.

### v0.6.8.6

- 🎨 Refine the layout of App update and Solution history pages.
- 📷 Restore the stable camera flow and allow beta installations to update to this stable release.
- 📌 `versionCode` **87**.

### v0.6.8.5

- 🛠️ Clear completed grading drafts after saving results to solving history; retain failed and interrupted work for recovery.
- 🧪 Distinguish stable and optional test updates, with separate notes and downloads and explicit release channels.
- 🎨 Use the same theme-colour gradient on Learn, Stats and Settings home pages.
- 📌 `versionCode` **85**.

### v0.6.8.4

- 🎨 Unify selection styling in solving history and refine the Review filters and text/image question page.
- ✂️ Reduce the multi-image crop-skip hold to 1 second with haptic feedback, keep slide-up cancellation and clarify each confirmation step.
- 📌 `versionCode` **84**.

### v0.6.8.3

- ↩️ Add predictive back previews, cancellation and interruptible transitions to settings pages, preserving parent navigation and bottom-tab switching.
- 🎨 Remove extra space above the bottom bar on Notebook, Review and related pages.
- 📌 `versionCode` **83**.

### v0.6.8.2

- ⬆️ Improve version checks and compatibility metadata retrieval, add an official fallback and clarify update errors.
- 🎨 Add an optional system wallpaper background and improve theme readability, layouts and transitions.
- 🗂️ Sort notebook subjects by problem count or a custom drag order; improve review filtering and long-press selection feedback.
- ↩️ Improve bottom-tab switching and double-back prompts; add capture progress previews and retaking.
- 🔔 Keep the download completion notice for 2 minutes 30 seconds; update solving labels and simplify About.
- 📌 `versionCode` **82**.

### v0.6.8.1

- 📝 Preserve unsaved custom prompt drafts across rotation and window recreation.
- 🔋 Pause UI data subscriptions and date polling in the background; refresh on return while answers and backups continue.
- 🔔 Refresh download notifications only when progress or presentation changes, preserving cancellation, completion and permission recovery.
- 🗄️ Add local diagnostics and an explanation for system-deferred backups without treating scheduling delays as damaged data.
- 🖥️ Refine content on About, Data management, App updates and related pages.
- 📌 `versionCode` **81**.

### v0.6.8
- 🛡️ Stop installation when installer permission cannot be queried, instead of assuming authorization.
- 🔔 Unify Android 16 download progress and completion notifications while preserving permission, cancellation and recovery checks.
- 🛠️ Remove obsolete platform branches and improve build-bound resumable release verification and explicit same-version replacement.
- 📌 `versionCode` **80**.

**The versions above support Android 16+ only.**

---

### v0.6.7.9
- 🛡️ Check package compatibility metadata before updating; block incompatible downloads and installations, and recheck restored downloads and packages before installation.
- 💾 Fix answer and follow-up updates being blocked after a problem with the same ID is saved again or restored from backup following deletion.
- 📦 Releases automatically include the minimum Android version and APK digest, without guessing compatibility from version numbers or release notes.
- 🔒 Fix conversation rendering failures caused by special characters, update the math renderer to address a known denial-of-service vulnerability, and restrict page access permissions.
- ⬆️ Fix an incorrect system-requirements warning caused by an already installed download record when reopening the update page after upgrading; keep compatibility checks for newer releases.
- **This is the final update for Android 9–15.**

### v0.6.7.8
- 📷 Fix rotated-photo downsampling crashes on Android 9 and unify orientation and black-and-white crop detection. Photo capture decodes, compresses and imports images in the background; preserve order and progress if any input cannot be read.
- 💾 Similar problems saved during generation now receive the final answer or interrupted fragment. Persist follow-up deletions before switching problems.
- 🛡️ Stop the answer foreground service promptly at the Android 15+ time limit to prevent system termination for an overdue service.
- 🧪 Select checks by change scope and reuse valid local results and candidates from the same CI run. Release gates verify source, artifacts and individual test evidence.

### v0.6.7.7
- 📷 Fix whole-image submission and mode changes in two-image capture; gallery selection remains available without camera permission or a working camera
- 💾 Save photo and crop progress locally, preserving image order, crop results and the original problem ID on recovery; clear it when explicitly returning home or discarding
- 📂 Distinguish corrupt backups from local storage and read failures; incomplete checks report failure and stop cleanup or automatic scheduling
- 🔔 Verified downloads show “APK download complete” and a brief “Done” chip, followed by a dismissible completion notification

### v0.6.7.6
- 🛡️ Review transitions check pending-save conflicts; similar-problem saves capture a complete snapshot to prevent stale or mixed conversations
- 🧠 Classification is bound to its session; similar-problem follow-ups retain the standard answer and earlier images, sending the current question once
- 📂 Distinguish invalid folders from temporary access failures, preserving automatic backup preferences for recovery
- 🔔 Separate review and answer notification destinations; cache the installed APK digest to avoid repeated update-page reads
- 🎨 Android 16+ uses official progress-style download notifications; progress bars follow the app theme and the capsule keeps a separate percentage

### v0.6.7.5
- 📂 Guide backup folder setup after the first Key entry; remind when missing or unavailable, with a skip option. A selected folder enables daily backups; otherwise the switch stays off and disabled
- 📝 Restore icons in early release notes; label and link the backup folder, and clarify support for Bak v3 JSON imports

### v0.6.7.4
- 🔔 Update downloads show ongoing progress, waiting and verification results, with a cancel action; tapping opens App update
- 🛡️ Uses standard Android notifications and background downloads. Downloads continue when notifications are unavailable; notification settings are accessible from App update
- 💊 Live capsule display depends on the phone system, without an Honor-specific SDK; appearance still requires device verification

### v0.6.7.3
- ⬇️ Android manages update downloads independently of the page; returning restores the task and progress
- 📶 Network interruptions retain the system task for retry and supported resumption; ended tasks can be downloaded again or canceled explicitly
- 🛡️ Verify the private APK copy for size, digest, signature and version, and recheck before installation; replacement assets keep separate tasks

### v0.6.7.2
- 📁 Localize the internal-storage path label; returning to Settings after a tab switch opens its main page, while rotation keeps the current child
- 💬 Center feedback text in content-sized bubbles; automatic backup failures show a short message with a blue details link to Backup management
- ✋ Simplify manual backup and import text; export initially opens the automatic backup folder while allowing another location
- 📂 Show the folder on one line with leading truncation; separate counts and time, and expand automatic backup notes on demand

### v0.6.7.1
- 📂 Count manual and automatic sets directly from the selected folder; multipart sets count once and read failures are separate from zero
- 🗓️ Back up once per local calendar day; skip if any complete manual or automatic backup exists today, otherwise back up even unchanged content
- ⏱️ Automatic plans use a 30-second delay; Back up now replaces a pending automatic plan
- 🛡️ Immediate backups remain durable system work independent of the page; repeated taps do not interrupt running backups

### v0.6.7
- 📦 Backup v4 uses native ZIP compression, image deduplication and bounded streaming; large backups split into parts
- 📥 Select all parts together for validated, confirmed import; legacy v3 JSON remains supported and existing question IDs keep local content
- 🗄️ Opening the app schedules automatic backup; unchanged content is skipped, with cancellation, retry and completed-stage reuse
- 🛡️ Register success only after all parts pass read-back verification; if more than 3 complete automatic sets exist, keep 2 before starting and 3 after success, including old automatic names
- 🏷️ Use the ZIP extension consistently to avoid duplicate suffixes: SABak-timestamp.zip or SABak_auto-; multipart files append -1of3. Manual, corrupt, incomplete and unrecognized files are preserved
- 💬 Automatic backup success shows a bubble only while the app is visible; background completions never replay later
- 🔔 Empty notebooks do not start automatic backups; final background failures post a system notification that opens Backup management
- ↪️ Prepared exports can be saved or canceled after returning to the backup page, keeping the controls usable

### v0.6.6.12
- 💾 Harden conversation draft saving and recovery; old-request cleanup no longer affects new drafts, and completed drafts remain until saved to the notebook or history
- 🧹 Explicit learning-data erasure also removes conversation drafts and stops old writes; confirmation now includes history and drafts
- 📖 Distinguish review-save failures from next-question loading failures; retrying after a successful save no longer records another review or advances progress twice
- 🛡️ Read large questions, answers and original images in chunks with short list previews; full-text search, duplicate checks and backups retain complete content
- 🔢 Preserve original Markdown subquestion numbers, including non-sequential numbering
- 🌐 Translate statistics mastery labels with the interface language and complete messages in all four languages

### v0.6.6.11
- 🎨 Share title-only cards across Data and AI management; move descriptions into subpages and add a matching card for Technology & compatibility
- ✍️ Give Custom instructions its own AI management subpage, preserving keyboard-aware saving and updating back navigation
- 📝 Restore emoji in v0.6.6.9/v0.6.6.10 changelogs and About feature/privacy items

### v0.6.6.10
- ⚙️ Move automatic backups into Backup management; split AI settings into API management, AI cropping and Solving settings with consistent back navigation
- ↩️ Undo question, history or category deletion within 3 seconds before it is committed
- 💬 Preserve old answers until regeneration succeeds; save completed follow-ups promptly and recover interrupted conversations from local drafts
- 📖 Keep the selected review scope, advance only after saving, require confirmation of AI grading suggestions, fix calendar-day calculations and label estimated statistics
- 📥 Import categories and tags only for new notebook questions; clarify rescue export
- 🛡️ Load card images on demand and long conversations in chunks; stop backups exceeding safe memory limits without changing existing data
- 🛠️ Isolate request cancellation; improve reminder intents/scheduling and update cancellation, temporary files and background verification

### v0.6.6.9
- 📝 Tighten number cleanup to preserve circled subquestion numbers repeated across conclusions and derivations
- 💾 Open, write and close backup exports in the background, reducing UI stalls and extra memory; keep operations busy until completion
- 💬 Detect AI output limits and interrupted streams, retaining received content for manual continuation or retry without automatic repeat requests
- 🛠️ Remove the local signing hash dependency and clarify release failures without exposing credentials

### v0.6.6.8
- 💾 Keep routine saves silent; show backup and operation results in floating Material 3 capsule snackbars without resizing pages, with longer red failure messages
- 🛠 Failed saves remain accessible for retry/export from the snackbar or Data management; report backup export success only after writing the file
- 🖼 Use “View images” for both original-question and follow-up attachments, localized in four languages
- 📝 Request a divider between conclusions and detailed solutions; preserve original subquestion numbers and match each result to its derivation, allowing normal step numbers
- 💬 Short follow-ups and concept explanations need no full solution template; existing answers and DB v10 / Bak v3 remain unchanged

### v0.6.6.7
- 💾 Retain recovery copies after save failures; restart recovery, retry and manual export preserve conflicting edits until explicitly discarded
- 🛠 Leaving a notebook entry saves answer content only, preserving current category, tags and review state; resolve pending saves before import or clearing
- 🔒 Remove raw AI response debug logging
- ⬆️ Compare same-version APKs by embedded UTC build time matching their filenames; digest fallback supports legacy APKs and avoids identical-content prompts
- 🧪 Strict JVM tests and isolated emulator data tests on release tags or manual checks; DB v10 / Bak v3 unchanged

### v0.6.6.6
- ℹ️ Update About and project documentation: Android 9 minimum, fast/thinking modes share deepseek-flash, and clarify direct AI requests, label analysis scope and local exports
- 💾 Backup failures retain a reason and time in settings; a verified successful write clears the warning. Changing folders resets status, with no new startup dialog
- 📝 Label organization drafts save locally and restore only against matching labels and links; preview is required again before merging. Save failures are shown and drafts can be explicitly discarded
- 🔎 Keep the last 20 local AI analysis diagnostics, with manual export and clearing; only timing, mode, status and counts, without label names, questions, raw responses or keys
- 🧪 Add Android database and file checks in an independent app package and a unified developer check entry; DB v10 / Bak v3 remain unchanged

### v0.6.6.5
- 🛠 Updated APK under the same version: correct thinking-mode label analysis parameters that caused immediate HTTP 422 errors; show specific failure reasons and status codes, with version and data formats unchanged
- 🏷 Improved label suggestion parsing: request JSON output, retain valid editable candidates, and distinguish truncated, empty and malformed responses; candidates start unselected and conflicts are still checked before merging
- 🏷 Organize labels with AI suggestions based on the full label list and usage counts; edit source groups and names manually, then preview and confirm merges
- ↩️ Merge and deduplicate links transactionally while preserving problem IDs; undo the latest merge after restart, with conflict checks that preserve later edits
- 🔒 Analysis sends only label IDs, names and question counts, excluding question text, answers and images; it is separate from solve sessions and can be canceled
- 💾 Main DB stays v10; undo uses a separate local auxiliary database. Successful merges replace prior undo; backup stays v3, with undo invalidated before import or clearing data

### v0.6.6.4
- 💾 Read back automatic backups and verify SHA-256 before pruning old copies; serialize backup and cleanup, and attempt to remove incomplete new files after failure
- 📥 Read and validate backups in the background with status feedback; decode images per problem to reduce peak memory while preserving transactional rollback
- 🕘 Show failed history saves with a retry action; retain the latest snapshot and original ID without recreating deleted or expired records
- 📌 DB v10 and backup v3 unchanged; no new data migration

### v0.6.6.3
- 🛠 Updated APK under the same version: confirm the first successful database upgrade separately from later no-op checks; show DB / Bak and actual schema versions, with an amber warning for legacy data
- 🆔 Permanent problem IDs combine a UUID and first-input timestamp; DB v10 migrates notebook, image, tag and review references after saving a database copy
- 🕘 Create history only on initial photo/text submission; notebook/review opens add no records, and follow-ups, retries and regeneration preserve original ordering
- 💾 Backup v3 deduplicates by permanent ID, preserves local content on repeated imports, and rejects invalid images or references instead of silently merging by text
- ⚠️ v1/v2 backups are no longer accepted. Export a new v3 backup after upgrading; existing notebook data migrates automatically, with missing times inferred from neighboring old IDs

### v0.6.6.2
- ℹ️ Credit GPT-6.1 Sol alongside DeepSeek-V4/V4.1 in the About page
- 🕘 Use a consistent timestamp fallback when pruning search history to avoid removing valid records
- 📚 Open linked notebook problems with their latest content, category and tags; view history independently when the linked problem is deleted
- 💾 Isolate save state between problems, save related data in transactions, and append only missing original images
- 🛠 Tie data upgrade prompts to the DB version rather than app patches; show “No upgrade is needed” when nothing needs updating
- 📌 DB v9 and backup v2 are unchanged; old images without a remaining source cannot be recovered, and further manual repair improvements are deferred

### v0.6.6.1
- 🧭 **Navigation turned into an explicit page tree**: new `ui/AppNav.kt` (single source of truth for every jump). The solve screen is now told where it was opened from (home / camera / grade / history / notebook / review) and its back button follows that — fixes "open a photo question from **search history**, press back, and land in the **camera**"
- 🔤 **Unified top-bar title size**: settings sub-pages (AI / language / theme / notifications / data / update / about), search history, notebook and review now match the **solve screen** (19sp; the back arrow stays 21sp)
- 🕘 **Search history title no longer has an emoji** (the home button keeps it)
- 📄 Fixed the missing blank line between v0.6.6 and the previous release in the in-app changelog

### v0.6.6
- 🗂 Data structure upgrade: multi-image questions are now stored properly (new `question_images` table); **v8 and older data migrate automatically**, with a DB file backup taken before migrating
- 💾 Backup upgrade: now includes **all original images + search history**; import is transactional (rolls back on failure) and **old backups still import**
- 🛠 Data management split into **four sub-pages** (Backup: export / import, Auto backup, Crash logs, Upgrade & Repair); **"Clear all data" moved to the Data management top page** (red danger row, requires typing the confirmation phrase)
- ⚠️ After updating, the first launch warns if legacy data is detected (skippable, no repeat nagging)

### v0.6.5.3
- 🐞 **Fixed one of two images still being lost**: the reader used to keep only the main image whenever it existed, while the rest live in the conversation — so a two-photo question showed a single image. The main image and the conversation images are now merged (deduped by base64, max 3) across the notebook detail, review self-test and search history

### v0.6.5.2
- 🎛 **Removed the live AI classification when opening a question or the category dialog**: subject and knowledge tags now come only from the presets captured while solving; opening a saved question just reads what is stored, defaulting to "no category + no tags". This also fixes the AI silently rewriting a stored category, and tag checkboxes being hard to untick
- 🖼 **Review now shows every original image** (multi-photo / text+image questions no longer show only the first page, up to 3)
- 🔤 **Unified button size**: the notebook's 🔍 / sort / manage buttons now use the very same size constant as the solve screen's ⏸ stop / 🔄 regenerate buttons (13sp)

### v0.6.5.1
- 🐞 **Fixed missing original images after saving to the notebook** (more likely with multi-photo or text+image questions): the images were always in the conversation, just never written to the dedicated image field — saves now include the main image, and older entries are back-filled automatically at launch (notebook cards, review self-test and backups all recover)
- 🔤 **Unified button size**: the 🔍 search / ⇅ sort / manage buttons at the top right of the notebook now match the solve screen's toolbar (12sp → 13sp)
- 🔤 **Unified selection toolbar**: in search history and the solve screen, the select-all / delete / done buttons now use the same size as the solve screen's buttons (the notebook's tighter selection bar stays as it was, by request)

### v0.6.5
- 🕘 **New "Search history"** (5th entry on Home): solved questions are kept automatically, so leaving without tapping "Save to notebook" no longer loses them; grouped by day, tap to continue asking, long-press for multi-select delete; the latest 200 entries / 30 days are kept, original images included
- 🐞 **Fixed the crop page's top edge occasionally jumping to the very top**: a "discard if it touches the edge" guard had been written but never wired up — it is now active; the selection is locked as soon as a finger touches down; after a pinch-zoom ends or the image changes the selection is pulled back inside the image; the four corner hit areas gained outer bounds
- 🐞 **Fixed search records being grouped under "Earlier" and even deleted by cleanup**: follow-up / regenerate wrote a zero timestamp, so the record was treated as older than 30 days and removed; the write is fixed and a fallback added
- 🎛 **The custom-instructions Save button now follows the keyboard** (the page stays clean when the keyboard is closed, and the button sits right above the keyboard when it opens); the leftover gap between the keyboard and the input box is gone
- 🏠 Home entries are now laid out as: Solve by photo / Ask with text-image each on its own row → Notebook · Review → Search history (no more "…")

### v0.6.4.5
- 📚 **"Save to notebook" now occupies exactly the same slot as "📁 Category"**: it uses the same "📁 + 5 CJK characters" width with left-aligned text and the spare width left blank on the right, so the button group no longer shifts when the two states switch
- ⌨️ **Fixed the custom instructions field being covered by the keyboard**: settings sub-pages now avoid the keyboard (the page moves up when it opens), and the input box has a maximum height with internal scrolling so the caret stays visible

### v0.6.4.4
- 🐞 **Fixed "custom instructions never applied"**: the custom prompt in Settings → AI management had **never actually been sent** (the code existed but was never wired up). It is now included when solving, grading, asking for similar questions and following up
- 🐞 **Fixed "the AI could not see your existing categories"**: the prompt sent to the model **always said "known categories: (none)"**, so it invented subjects — which is how duplicates like "大学物理" and "物理" appeared. The cause was a Flow that nobody subscribed to, so it always returned an empty list
- 🐞 **Fixed "some text gets bolded by mistake"**: inputs like `2**10 and 3**5` were rendered as bold spanning unrelated words (fixed in the chat view, the detail page and the list preview)
- 🐞 **Fixed "backing out of a text question returned to the camera screen"**
- 🐞 **Fixed "the thinking bubble could not be selected or deleted after aborting"**
- 📁 **Fixed the category button cutting off the 5th character** (e.g. "信号与系统" showed only the first four): the width is now measured with the button's real type style, with a fallback to the actual label width
- 🔤 **Default prompt cleaned up**: removed redundancy and the self-contradictory "every line must start with a number" rule; asterisks are no longer used for emphasis in the prompt
- 📷 **Removed the "question area auto-selected" hint** on the crop screen: its appearance and disappearance changed the bottom height, which made the picture jump

### v0.6.4.3
- 🐞 **Fixed the blank "next problem" page after switching problems in review**: going from a **photo problem** to a **text problem with no original image** made the problem's "light / heavy query redirect" **load it once more** and wipe the review mode along the way → the **Familiar / Vague / Forgot buttons disappeared**, the title went back from "Review x/n" to "Notebook", and it occasionally got **stuck forever on the centred blank "next problem" page**. Review mode is now kept through the whole loading chain, and the clear-screen flag got a fallback reset as well
- 📁 **The "subject" button fixed again**: the **emoji never changes size any more** (it used to shrink together with the subject name); the button is now **wide enough for 5 characters** (the button group on the right therefore shifts left by one character); past 5 characters it **no longer shows "…"** but **shrinks the text** so that as much as possible fits (only a really long name wraps to a second line)
- ↩️ **One back-arrow style for all sub-pages**: now the **21sp large arrow** used on the solve page (the settings sub-pages had lost their text size, so they were too small and, because of the container padding, looked **pushed to the right**); "← Back" on the camera / grading pages was unified as well
- 🔄 **The "App update" page is now kept in sync with the README version log**: patch releases such as `0.x.x.x` **also have their notes written into the in-app "What's new"** (previously README only), and the older four-segment versions were filled in too

### v0.6.4.2
- 📁 **The "subject" button has a fixed footprint**: its size is locked to the measured width and height of the "⏸ Stop generation / 🔄 Regenerate" buttons, so with a short subject name (≤4 characters) the emoji no longer shifts and space stays on the right; longer names step the text size down (13 → 12 / 11 / 10 / 9sp), and 9 or more characters may wrap to two lines
- 📷 **One tap on the camera focuses and adjusts exposure**: tap-to-meter now explicitly uses `AF|AE|AWB` and adjusts the exposure compensation within about 1.2 s from preview-frame brightness statistics — it fixes "photographing a computer screen comes out overexposed and unreadable"
- 🔍 **Bigger focus box**: it used to be a hard-coded 42 pixels (only about 12dp on a 3.5× screen), which is far too small; it is now in dp (about 64dp for the whole box)

### v0.6.4.1
- 🔤 **Solve-page top bar one more 1sp smaller** (user request): ⏸ Stop generation / 🔄 Regenerate / 📚 Save to notebook are all 14 → **13sp**; the auto-scaling of "📁 subject" steps down to **11 / 10 / 9sp** as well
- 🧠 **Expanding / collapsing the thinking block "remembers your last action"**: once you toggle it by hand, the **whole app, across problems**, follows your last choice (expanded / collapsed), stored as the local preference `think_expand_pref` shared by the solve page and the similar-problems page
- 🐞 **Fixed accidental long-presses on Honor devices**: after tapping the system "dynamic capsule" and then lightly tapping the screen, the app occasionally treated it as a "long-press bubble" and entered multi-select — the web side now resets on `touchcancel` / focus loss and guards the long-press timeout, and the native side resets the gesture state when the window regains focus

### v0.6.4
- ⛔ **"Export this problem" has been dropped**: the long-image / PDF export **never managed to include everything** (it still cut off at the bottom after several fixes), so the feature is gone for good — the 📤 button in the solve-page top bar, the export dialog and all related code and strings were **deleted**. To keep a copy, use a system screenshot or copy the question and answer
- ✂️ **New "AI cropping" switch** (Settings → AI management, right below the API usage stats): when it is off, **no AI is called at all** — boxing uses the local algorithm only (faster and no tokens spent), the "AI crop timeout" setting below is hidden, and the manual "AI detect" button on the crop screen is hidden too
- 🔤 **"Stop / Regenerate" show their labels again**: the top bar now reads "⏸ Stop generation" and "🔄 Regenerate" instead of icons only, with the same text size as the other buttons such as "Save to notebook"
- 🔍 **Notebook search button size**: the 🔍 in the title bar now **matches the text size** of "Sort / Manage" next to it

### v0.6.3.3
- 🐞 **The real cause of the truncated export found and fixed**: the content height was measured **before the original photo (base64 `<img>`) had finished loading** → the bitmap was shorter than the real content → the bottom got cut off. The page now **waits for both images and fonts** before reporting its height, and **the native side measures once more before capturing and takes the maximum**; the page height calculation now includes the **footer** as well
- 🔤 **Tighter top bar**: the 📤 and ⏸/🔄 icon buttons are explicitly tightened to 44dp wide with 2dp padding (the TextButton minimum width of 58dp makes a single emoji look very "loose"), and the whole group sits against the right edge

### v0.6.3.2
- 🐞 **Export truncation fixed again**: it now **shows the export page and copies the real screen pixels band by band with PixelCopy**, no longer relying on offscreen `draw` at all (on a real device that either drew nothing or only the first band); every band must **have content** and **differ from the previous one**, otherwise the band is retried, and if it still fails the app clearly reports "export failed" — no more half screenshots
- 🔤 **Top-bar buttons unified**: "Stop / Regenerate" **keep only their emoji** (⏸ / 🔄); **📤 export, ⏸·🔄 and 📚 save now share one text size**; the "subject" button (shown after saving to the notebook) **still scales with the subject-name length**

### v0.6.3.1
- 🐞 **Fixed exports coming out completely blank**: offscreen capture used to lay the page out at **1 pixel wide** (the PNG became a 1px white strip and the PDF came out as 11 nearly empty pages) — the page is now laid out at its real width when mounted, **software rendering** is forced, and the result is checked for "ink"; a blank capture reports a failure instead of saving an empty image
- 📄 **PDF now goes through Chromium printing**: the WebView itself outputs a **vector** PDF paginated to A4 (far sharper than slicing a long image); exports always use a light background (saves ink and looks better)
- 📤 **Export button moved to the left of "Regenerate"** (solve-page top bar); **the solve-page title is 2sp smaller again** (21 → 19)

### v0.6.3
- 📤 **New "Export this problem"**: the 📤 button at the far right of the solve-page top bar opens a dialog where you **pick the format (PNG long image / PDF)** and then **choose where to save it** (system "Save to file"). The export contains the original photo, question, answer and subject / key points, with formulas typeset by KaTeX; PDF is paginated to A4 automatically
- 🔠 **New "Text size" setting** (Settings → App theme): Small / Default / Large / Extra large — changes text inside the app only, **UI and conversation together**, system settings untouched
- 🔤 **Overall type scale tightened**: button labels are **1–2 steps smaller**; sub-page titles (notebook / review / solve / similar / settings sub-pages) are **1 step smaller** and sit a little closer to the "←" arrow
- 📚 **Notebook**: the title no longer carries the count (just "Notebook"); the count moved next to the "Search by tag" button and shows the **filtered** number — "N problem(s) in the current list"
- 🩹 Fixed: the on-demand backfill of subject/tags when opening an old problem was occasionally dropped (another classification request was in flight) — it now **retries once automatically**, so old problems get their subject and tags more reliably

### v0.6.2
- ✏️ **Redo a review problem by hand**: while reviewing, tap "✏️ Redo by hand" → photograph the solution you wrote on paper → the AI grades it and **updates your mastery from the result** (no need to tap Familiar / Vague / Forgot); jump straight to the next problem, and text-only problems can be redone too
- 📚 **Notebook sorting**: a new "⇅ Sort" menu — recently added / oldest first / next review time / most reviewed / **least mastered first** (your choice is remembered)
- 🔎 **Review filters**: filter by **subject** or show "**weak ones only**"; progress counts the filtered list, and an empty result says so explicitly
- ⚠️ **Duplicate check when saving**: if a problem with the same text already exists you get a "**maybe already saved**" warning showing the existing text, and you can still save it (a hint, never a block; small differences between two transcriptions of the same problem are detected too)
- 🐞 Fixed: **switching the notebook sort jumped to the bottom of the list** (it now returns to the top); switching the review tab or filters does the same
- 🐞 Fixed: **the duplicate check missed the same problem photographed twice** — the AI never transcribes it word-for-word identically (LaTeX marks and punctuation vary), so matching is now "same or highly similar text" (short questions are still compared exactly, to avoid false warnings)
- 🗄 **Automatic backup**: Settings → Data → "Auto backup"; pick a folder and the notebook is backed up to JSON on the **first launch of each day**, keeping only the **last 3** (local only — no network, no upload)
- 🌙 **Dark theme for the conversation area**: bubbles, body text and the thinking block now follow the theme; previously bubbles stayed light grey and the grey thinking text was nearly unreadable in dark mode
- 🐞 **Crash logs (local only)**: crashes are recorded with version / device / full stack trace (last 10 kept) and can be **exported as txt** or cleared from Settings → Data
- ⬆️ **Update page improvements**: the **release notes of the new version are shown in-app**, you can "**skip this version**" (no more red badge for it), the **last check time** is displayed, and failed checks/downloads offer retry
- 🔍 **Engineering**: 47 unit tests added (answer parsing / version comparison / backup pruning / sorting / duplicate check / string tables), run automatically on every push

### v0.6.1.4
- 🗄 **Automatic backup**: Settings → Data → "Auto backup" — pick a folder and the whole database is backed up to JSON on the **first launch of each day**, keeping only the **last 3** (older ones are cleaned up automatically, and only files it wrote itself are deleted); the backup can be restored straight from "Import backup", local only — no network, no upload
- 🗄 A backup has to survive "uninstall / clear data", so a folder must be picked once for this to take effect, and the files are **deliberately not written into the app's private directory**; you can trigger it any time with "Back up now" or switch it off
- ⬆️ From this version on the "what's new" text is written directly in the Release — the app's update page shows it

### v0.6.1.3
- 🔍 **Unit tests introduced for the first time**: a new `app/src/test` (22 cases) covering the **pure logic that had bitten us repeatedly** — answer parsing (question echo, circled-number leakage, body text must not be truncated), version comparison and the "new build of the same version" rule, and the keys / placeholders / traditional conversion of the four string tables; run locally with `tools/run-unit-tests.ps1` and by CI on every push
- ⬆️ **Update page improvements**: a new version now shows **its release notes** directly (cleaned-up Markdown); a new "**skip this version**" option (silent startup checks no longer raise a red badge afterwards, and it can be cancelled at any time); the **last check time** is shown; failed checks / downloads offer "**Retry / Download again**"

### v0.6.1.2
- 🌙 **Dark theme for the conversation area**: bubbles, body text and the thinking block on the solve / similar-problems pages now use CSS variables and follow "App theme" (light / dark / follow system); previously bubbles stayed light grey and the grey thinking text was nearly unreadable in dark mode
- 🐞 **Crash logs (local)**: crashes are recorded on the device (version / device / thread / full stack trace, last 10 kept), and Settings → Data can **export them as txt or clear them**; **local only — nothing is uploaded**

### v0.6.1.1
- 🔁 **New builds of the same version are detected too**: even when the version number is unchanged, an update is offered if the published build is newer than your install and the content really differs (SHA-256 differs) — a safety net, since normal releases still go by version number
- ℹ️ "About → App info" gained a **MIT License** line (small grey text)

### v0.6.1
- ⬆️ **New "App update" page** (in Settings, between Data and About): shows the current version, checks for updates, downloads, and **verifies twice before installing** (SHA-256 + signing certificate); a failed check deletes the file
- 🔴 **When an update is available**: a **red badge ①** appears on the Settings icon and on the App update entry; the app also does one **silent check** at startup and stays quiet on failure or when up to date
- ⚙️ **Settings reorganised**: "API" now lives inside the "**AI management**" sub-page (at the very top), and "AI settings" was renamed accordingly
- ℹ️ **About page reworked**: only "App info" (version / description / **project link** / copyright) plus a new "**Features**" section
- 🌐 Thinking-block labels and the Features section follow the UI language (简体 · 繁體 · English · 日本語 · 한국어)
- 🔁 **New builds of the same version are detected too**: even when the version number is unchanged (e.g. a replaced release asset), an update is offered if the published build is newer than your install and the content really differs

### v0.6.0
- 🧠 **AI thinking stream**: the model's reasoning is shown in small grey text at the **top** of each answer bubble; while thinking it **streams in and follows along automatically** (scroll up to stop following), and when thinking ends the view **jumps to the end of the reasoning / start of the answer**
- 💭 Two toggle points: the "Thinking" header at the top and the blue "Collapse / Expand thinking" line at the **bottom** of the block (no need to scroll back up when it is long); works for photo solve, grading, ask-with-images and notebook Q&A
- ⚙️ "Solve model" in AI settings is renamed "**Solve mode**" (**the model is unchanged**, it is only the thinking switch): ⚡ Fast = no thinking (and **no thinking block**); 🧠 Deep thinking = reason first, then answer
- 🐞 Fixed **answers disappearing after generation**: removed a cleanup rule that cut everything from "Category / Key points" to the end of the text whenever the model omitted the "Solution:" marker
- 🐞 Fixed **the question being echoed inside the answer**: echoed meta lines are now stripped even when prefixed with a number (e.g. `① 題目：…`)
- 🐞 Fixed **stray ④ markers** in front of each paragraph (the model copied the format marker into the content)
- 🛠 Unified the solve / text-solve / grading prompts on one **numbered-label** protocol and removed contradictory duplicated instructions
- 🐞 Fixed the **previous answer's thinking being overwritten** by the current turn on follow-up questions
- 🐞 Fixed "stop the running generation + clear the capture cache on returning home" silently doing nothing due to a wrong condition
- 🌐 The thinking block labels (Thinking / Collapse thinking / Expand thinking) now follow the **app language** (简体 · 繁體 · English · 日本語 · 한국어)

### v0.5.9
- 📝 Text-first questions: for photo/ask the stem is faithfully transcribed by the AI (LaTeX rendered with KaTeX) and the original photo is collapsed behind a View original photo toggle; review still shows the photo for self-testing
- ✍️ New AI settings page: crop timeout plus a custom instructions prompt that is appended to every solve / grading / similar-question request
- 📚 Light typography in list previews: LaTeX in the review and notebook lists is converted to readable symbols such as ∫ ∮ ∑ ≤ √ ρ
- 🐞 Fixed a crash while reviewing: the queue query selected q.* and pulled image blobs, exceeding the 2 MB CursorWindow limit
- 🔁 Fixed the same question appearing twice in a row during review: interval step 0 was 0 days so it stayed due today; the minimum interval is now 1 day, so a question reviewed today will not show up again today
- 🖼 Fixed missing original photos in the notebook: the list now uses a light query and the image is loaded by id when opening a question
- 🎯 Fixed review opening as a plain mistake view: completing the image asynchronously overwrote review mode
- 🏠 Fixed review finishing back to the launcher: it now navigates home explicitly

### v0.5.8
- 🎨 App theme: theme colours — 8 preset swatches plus a custom colour picker (hue / saturation / brightness); the settings entry is now an App theme sub-page
- 🐞 Fixed a crash while reviewing: the review queue selected q.*, pulling each question image blob, so a longer queue exceeded the Android 2 MB CursorWindow limit and crashed (it looked like tapping Familiar returned to the home screen); the list now selects light columns only and loads the image/session by id when a question is opened
- 🔄 Fixed review opening as a plain mistake view: completing the image asynchronously overwrote the review-mode flag
- 🏠 Fixed the review finishing back to the launcher: after the last question the app now navigates home explicitly

### v0.5.7
- 🗂 Auto-classification: the AI decides the subject and knowledge tags and pre-selects them, **preferring your existing subjects** (near-synonyms and broader/narrower ones count); old questions are back-filled and written to the database
- 🧩 One shared solve screen for photo / grading / text-image ask / notebook; returning from review no longer jumps to the camera
- ✏️ Text-image ask now opens a large input box right away (one hop less), and the title matches the session
- 🗑 Delete moved into the category dialog: red "Delete" at the bottom-left plus a confirm sheet; you stay in the current session
- 🛠 Fixes: input bar pushed away while generating, photo/question not visible while generating, notification jumping home, bubble lost after stopping, pre-selection request cancelled, tag row not scrollable or deselectable
- 🛠 More fixes: in two-shot mode the crop page showed the wrong (first) image after the second shot — now the second image is shown and the first-step button reads "Upload next image"; saving to the notebook works with images and no text; the title stays "Ask with text/image" after asking; a network failure (e.g. 503) while solving no longer looks like the ask screen, and the captured question is kept

### v0.5.6
- **📊 New "Learning stats" page**: bottom bar now has three tabs — 📚 Learn (renamed from Home) / 📊 Stats / ⚙️ Settings
- 📊 Stats: reviewed today, last 7 days, day streak, due now; mastery split (not started / learning / mastered); mistakes added in the last 7 days (toggle to **30 days**, with a Y axis); subject breakdown
- 🛡 **Android 17 compatibility fixes**: exact-alarm permission added with graceful fallback (reminders no longer silently stop); crop screen bottom buttons now avoid the gesture bar

### v0.5.5
- 🧭 **Menu change**: "Ask directly" moved from the photo page to the **home screen**, between "Solve by photo" and "Grade my work" (type or attach an image, no photo needed)
- ✂️ **Pre-selection in the crop screen**: the question area is framed automatically (local algorithm + AI in parallel, falling back to local/default on timeout); pinch to zoom, hold to skip
- ✂️ **Two-shot mode now crops every image**: each photo goes through cropping; press back on the 2nd to re-crop the 1st
- ⚙️ **Settings reorganized**: new "General" (API, AI crop timeout slider), plus "Personalization" and "Notifications & background"
- 🧹 Returning to Home clears all capture/crop caches

### v0.5.4
- ✏️ **Grading page rebuilt**: shooting the question (or question + your answer) now goes straight to a **full-screen grading page** reusing the solve UI, titled "Grading"; results and follow-ups are **fully streamed** and you can **ask with images** — "⏸ Stop grading" while grading, "⏸ Stop" while asking, "🔄 Re-grade" when idle
- 🧩 **Similar-problem page matches the solve page**: "🔄 Regenerate" (clears and re-generates), "📚 Save to notebook", "⏸ Stop"; the same bottom input bar (formula keys / gallery / images / send); "Show answer" is a **pill button at the bottom-left**, greyed out until the answer is ready
- 🖼 **Gallery now uses the system's ordered selection**: picks are numbered **1/2/3** and returned in tap order; **no photo permission needed**; two-shot mode can pick both at once (1 = question, 2 = your answer)
- 🛡 **Protected messages**: the **question** and the **first AI reply** (answer / grading result) **cannot be selected or deleted** (grey dashed circle); long-press is **disabled inside multi-select**
- 💭 **A bubble even with no content**: "**Thinking…**" until the first token arrives; after a stop the bubble is kept with a blue "**Continue generating**" line
- 📊 **Review bottom row is now a progress bar**; "Show answer" keeps the view still, "Hide answer" scrolls back to the top
- 🔁 Solve and grading pages **each remember single/two-shot mode**; the ↑/↓ button appears **only when the content exceeds one screen**
- 🔤 Page titles unified; fixed a stray "Delete / Uncategorized" button group on the grading page

### v0.5.3
- 🌊 **Streaming answers for solve and follow-up**: the answer appears while it is generated (with a trailing ▍ cursor), so long derivations no longer make you wait for the whole block
- **⏸ Stop** while generating; after a stop or a network drop the text already generated is kept and a blue "**Continue generating**" line appends at the end — tapping it **resumes from where it stopped** without repeating
- ☑️ **Long-press a message bubble to enter multi-select**: a pick circle at each bubble's top-right and a red border when selected; the bar has "Select all / Deselect all" on the left and "Delete / Done" on the right; system text-selection is suppressed in this mode
- ⬆️⬇️ Conversations open **at the top**; the round button at the bottom-right shows **↓** when at the top (scroll to bottom) and **↑** otherwise (back to top), both animated
- 🧩 **Similar-problem page is streamed too**: the question streams while the answer stays hidden until "Show answer" (the button is disabled until the answer is ready); follow-ups stream as well
- 🔁 Review: the title shows "**Review x/xx**"; moving to the next question now **clears the screen briefly, collapses the answer and scrolls back to the top**
- 🔤 Page titles unified to the large size (Solve / Problem / Notebook / Review); the notebook's multi-select title is small and gained a "Select all" button
- 🗂 Problem detail bar: Delete sits left of the category button; while generating, "⏸ Stop" appears and **Delete is disabled**

### v0.5.2
- 🧮 The **formula keyboard is now a slim strip**: by default it shows a single small "Formula keyboard" line; tap it to expand the symbol and LaTeX template rows, tap again to collapse — it no longer takes up input space
- 📱 The UI is now **locked to portrait**: rotating the phone no longer rotates the app (it was unlocked before, and some pages looked broken in landscape; large-screen devices may still ignore this lock)

### v0.5.1
- 🔍 Notebook: the search box no longer takes up space — open it with the **🔍 button** in the title bar; tap anywhere else (or press Back) to close it
- 🗂 Notebook gained a **"Manage"** entry: a vertical list of categories with **✏️ rename** and **multi-select delete**; deleting asks for confirmation and lets you choose **whether to delete the problems in those categories too** (otherwise they become "Uncategorized")

### v0.5.0
- ⚙️ Settings reorganized: new "💾 Data" page; "About" is now a subpage (app info on top, what's new below)
- 💾 Data management: export a backup (single JSON with images and full conversations) / import a backup (same-name categories and tags are reused, identical questions are skipped — nothing is overwritten) / erase all data (with confirmation)
- "🔑 API" now holds both the key settings and cumulative usage stats (call count, input/output tokens, resettable), with the note "API Key is always stored encrypted on this device"
- 📖 Review now hides the answer by default: recall first, then tap "Show answer"; a "i / n" progress line is shown
- 🔍 Notebook gained full-text search over the transcribed question and the answer
- 🧮 Direct questions and follow-ups gained a formula bar: ∫ ∑ √ π symbols plus LaTeX templates (fractions, sub/superscripts, integral limits…)
- 🔌 Redo mode has a reserved interface (hand-write your solution → reuse grading → auto-update mastery); UI comes in a later version
- 🔍 Images in conversations (solve / grading / similar problems) can be **tapped to view full screen**: pinch to zoom, drag to pan, close with ✕ or Back
- 🛡 "Erase all data" now requires **typing a confirmation phrase** and the confirm button is red; the "API Key stored encrypted" note is green

### v0.5.0_beta
- 🌐 New "App language": Settings → 🌐 Language, choose Follow system / 简体中文 / 繁體中文 / English / 日本語 / 한국어 — applies instantly, no restart
- 🌐 The whole interface is now multilingual: home, camera, direct questions, solving, grading, notebook, review, settings, notifications
- 🧩 "AI answer language" and "App language" are now two separate settings: one controls what language the AI answers in, the other the app's own text

### v0.4.9
- 🎨 Photo questions now show only the original image in the question bubble — the AI-transcribed text is gone (it is still saved; notebook, search and editing are unaffected, and older sessions behave the same). Direct questions still show what you typed
- 🌐 New submenu in Settings: 🌐 Language → AI answer language (follow system / follow question / 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Français / Español / Русский)
- 🌐 The language setting covers photo solving, direct questions, follow-ups, grading and similar problems (formulas stay in LaTeX)

### v0.4.8
- 🎨 Questions and first prompts render as a light-green user bubble with the original image everywhere: one-shot, two-shot, direct questions, grading, notebook and review
- 🎨 Review screen switched to a single column — full width, larger images
- 📦 R8 shrinking + resource shrinking became standard for release: APK about 4.4 MB (was about 30 MB), old sessions stay readable

### v0.4.7.5 (temporary version)
- 📦 Enabled R8 code shrinking/obfuscation + resource shrinking (release only); APK much smaller; old sessions stay readable
- 🆕 New submenu in Settings: 📝 What's new

### v0.4.7
- 🆕 Direct questions now appear in the conversation as "my question + attachments"
- ⚡ Performance: notebook/review images use caching + downsampled decoding (smoother scrolling, less memory)
- 📦 Size: removed redundant KaTeX .woff files (kept woff2); preview tooling moved to debug-only
- 🆕 Photo solving gained One-shot / Two-shot modes: two photos are solved as one problem
- ⚡ More micro-optimizations: in-memory API key cache; cached flows; reused time formatter; cached message list; skip WebView re-render when content is unchanged; cached filters; preloaded category/tag names

### v0.4.6
- 🔄 "About" is shown directly on the settings page instead of a submenu
- 🆕 Tapping the "generating" notification returns to the page that is generating

### v0.4.5
- 🆕 Settings became a submenu list (API Key / Theme / Review reminder / Background running / About)

### v0.4.4
- 🆕 Tag selection when saving a problem became a scrollable dropdown (AI suggestions kept)
- 🆕 Notebook tag filter became a "Search by tag" button with a multi-select dropdown

### v0.4.3
- 🔄 Model upgraded to DeepSeek-V4.1-Flash (deepseek-flash, native multimodal), vision and text unified
- 🐛 Fixed the "Solve" title being squeezed into an ellipsis by the right-side buttons

### v0.4.2
- 🆕 Tapping the review reminder opens the Review screen directly
- 🆕 "Enable auto-start" shortcut in Settings (Honor / Huawei / Xiaomi / OPPO / vivo)
- "✏️ Ask directly" in the camera screen: ask with text + 1–3 images
- 🆕 Reminder time picked with a wheel selector

### v0.4.1
- 🆕 After reviewing, jump to the next problem automatically (same subject first); exit only when today's list is done
- 🆕 Similar-problem practice: the AI writes a similar problem (question first, interactive); tap "Show answer" to reveal it
- 🆕 Review reminder notification: switch + time (once a day)
- 🆕 Notification permission request; reminders restored after reboot

### v0.4.0
- 🆕 Review feature: new "📖 Review" entry on the home screen
- 🆕 Ebbinghaus scheduling: new problems are scheduled at 0/1/2/4/7/15/30-day intervals (new review table, DB v7)
- 🆕 Review screen with Today / This week tabs, grouped by subject → knowledge point, two-column waterfall
- 🆕 Inside a problem the bottom shows Easy / Unsure / Forgot, changing when it appears next
- 🆕 Similar-problem API prepared

### v0.3.9
- 🆕 Knowledge tags: decoupled from subject categories, global, many-to-many
- 🆕 AI auto-tagging (up to 5, reusing existing tags), editable when saving or in details
- 🆕 Multi-select tag filter at the top of the notebook; tags shown on cards
- 🆕 Database v5→v6 (tags + question_tags)

### v0.3.8
- 🐛 Fixed: conversation body back to a single, stable scrolling WebView
- 🐛 Fixed: problem timestamps record the asking time and no longer change when you leave
- 🗂 Versioning convention: custom data files carry a 4-byte version header for future migration
- 🔐 Key policy: no keys in the source or repository

### v0.3.7
- 🆕 Batch category change / batch delete: long-press in the notebook to multi-select
- 🆕 Batch category: existing / new / uncategorized
- 🆕 Batch delete: permanent deletion with a confirmation dialog
- 🆕 Selected cards show a check mark and a green border

### v0.3.6
- 🆕 Problem categories: choose uncategorized / existing / new when saving; the AI guesses the subject
- 🆕 The AI prefers existing categories to avoid duplicates
- 🆕 Category filter at the top of the notebook; the category can be changed in details
- 🆕 Notebook switched to a two-column waterfall; "Uncategorized" added
- 🆕 Database v4→v5 (categories table)

### v0.3.5
- 🆕 Follow-ups can include images (1–3); the AI reads both text and images
- ✨ Input row restyled: rounded field + pill-shaped "Send" button
- 🆕 Follow-up bubbles show attachment thumbnails; attachments are saved with the session
- 🔄 Removed the "next problem" button; going back from Solve returns to the camera
- 🆕 Camera supports tap-to-focus with a focus ring animation

### v0.3.4
- 🆕 Grading: one-shot / two-shot capture; the AI checks correctness, points out wrong steps and explains
- 🆕 App icon letter changed to SA
- 🔄 Notebook deletion became a soft delete (restorable)
- ✨ Follow-up input avoids the keyboard; the gallery button is aligned with the shutter

### v0.3.3
- 🆕 App icon (white background + blue book); launcher name "Study Assistant"
- 🆕 Saving stores the whole conversation; tapping a problem continues the Q&A
- 🐛 Crop frame in bold red with draggable corners; APK naming convention

### v0.3.2
- 🆕 Bottom tabs: Home / Settings
- 🎨 Theme: light / dark / follow system
- 🔑 API key moved into Settings; About shows author and version
- 🐛 Fixed answer scrolling; answers shown as chat bubbles; first launch prompts for the key

### v0.3.1
- ✨ Markdown formatting in the answer area
- ✨ Fixed long-answer scrolling; wide formulas adapt with horizontal scrolling
- 🆕 "Retake" in the camera screen; "next problem" in the Q&A screen
- 🆕 "Gallery search" in the camera screen; collapsible original image on the answer page
- 🆕 Notebook shows the cropped original image and the LaTeX answer

### v0.3.0
- 🆕 Crop after taking a photo, or use the whole image
- 🆕 Regenerate + continue the conversation (multi-turn)
- 🆕 API key entered in the app (encrypted with Android Keystore)
- 🆕 KaTeX formula rendering

### v0.2 (early version)
- 🔄 Switched image handling to DeepSeek: images go straight to the DeepSeek vision model for recognition and solving; Baidu OCR removed

### v0.1 (project start)
- 🆕 Initial build: Baidu OCR for image recognition + DeepSeek for solving; photo search, AI solutions and the mistake notebook prototype

## Development and testing

Develop/feature branch → minimal necessary local validation → Beta testing on the user's device with ADB assistance when needed → scoped fixes and rechecks → conventional merge into main → one CI build → signing and release.

`tools/check-project.ps1` checks diffs and sensitive content by default. Use `-Compile` or `-TestClass` with a specific class/method only when necessary. No default full suite or emulator, and no duplicate local/online testing. Feature work must not silently expand testing requirements; ask for device feedback on interaction effects. See the [development guide](../development.md) and [AGENTS.md](../../AGENTS.md).

## Credentials and releases

CI manually builds an unsigned APK and identity manifest. It does not run application, tool or device tests by default; pushes, PRs and tags do not trigger duplicate builds. Use Conventional Commit prefixes such as feat:, fix:, build: and ci: for commits and merges.

After validation and merging/pushing main, use `tools/release.ps1 start --version VERSION` → `status` → `publish --run RUN_ID`. Keep the existing certificate and lineage local; verify APK identity and payload, then download and hash the published assets for archival. Preserve minimum Android requirements, release channels and immutable tags. Never commit or upload keys, signing materials, real study data, backups or phone logs to CI.

## API key and permissions

Enter your own DeepSeek key in Settings → AI management → API settings. Manage your account in the [official console](https://platform.deepseek.com).

Features use network, camera, notifications, review alarms/boot recovery, background execution and package-install permissions. Image selection uses the system photo picker. No location, contacts or microphone permission is needed. Notifications, exact alarms and background operation depend on system grants and power policy.

## License

[MIT License](../../LICENSE) · Copyright © 2026 ShizhenZhou
