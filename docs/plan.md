# Nutridata OCR App Plan

## Goal

Create an Android app that lets users log in to [tap.nutridata.ee](https://tap.nutridata.ee) and use their phone's camera and OCR to make adding new food products to their personal database easier.

## Prototype Stage

This is a prototype: a NutriData WebView with a separate Compose camera screen for live OCR supplemented by high-resolution photos, raw text, and nutrient extraction for English, Estonian, Latvian, Finnish, Lithuanian, German, and Polish. Russian is deferred. Login uses the website itself and still needs user verification. Reviewed label values can fill a new-food draft based on a similar food; saving remains a manual website action.

Keep automated tests minimal: add them only when needed to move to the next step. Use a build check for compilation and rely on the user to verify camera behavior and OCR accuracy with real labels on an Android device.

### WebView And TLS

- The app opens `https://tap.nutridata.ee/et/`. **Scan food** opens the scanner in another activity; returning leaves the current web page in place while its activity remains alive. Website cookies and DOM storage are enabled. External links open outside the WebView. Filling requires explicit confirmation; nothing is submitted automatically.
- On 2026-09-24 the server sent only its leaf TLS certificate, without its Sectigo DV R36 intermediate. Both the emulator and Samsung S23 rejected this incomplete chain.
- `network_security_config.xml` adds the bundled **Sectigo Public Server Authentication CA DV R36** as a trust anchor for exactly `tap.nutridata.ee`, with no subdomains. System CAs remain trusted; other hosts receive only system trust. Cleartext is disabled and SSL errors are still cancelled. This is APK-local configuration, with no device certificate installation or system-setting changes.
- This explicitly trusts the intermediate for this host, rather than simply caching a chain or pinning the current leaf. Leaf hostname, signature and validity checks remain enforced, but the path terminates at the bundled intermediate instead of validating its upstream root at runtime. Remove the workaround once the server supplies a complete chain accepted by Android; review it if the issuing CA changes or is revoked. The intermediate expires on 2036-03-21; bundled trust anchors need explicit maintenance.
- Certificate source: `http://crt.sectigo.com/SectigoPublicServerAuthenticationCADVR36.crt`, as identified by the site's leaf certificate. Its chain was independently verified with macOS system trust using `security verify-cert -c <intermediate.pem> -p basic` on 2026-09-24, not trusted solely because of that HTTP download.
- Intermediate SHA-256 fingerprint: `8C:54:C3:34:B6:6B:A4:E4:26:77:2A:F4:A3:F9:13:6C:19:A1:AE:C7:29:FD:B2:8C:53:5C:07:A5:A4:EF:22:E0`.
- Verified with a strict TLS handshake, `:app:assembleDebug`, and an emulator smoke test that reached **Logi sisse**, opened **Scan food**, and returned to the website. The fixed build has not been installed on the Samsung. Authentication and nutrition-form automation are not yet verified.

### Live OCR With Photos

- Keep the preview and live OCR active with a shutter button for extra high-resolution observations. Use CameraX still capture, prioritizing capture quality and the highest available resolution selected for the combined camera outputs, rather than freezing an analysis frame. Flash stays off to avoid label glare.
- Run OCR once per captured photo and feed the same line, token, confidence, and text-size metadata into both existing sampling pipelines. Multiple photos and live frames build evidence in one scan; a single photo is never replayed to manufacture confidence. Photos bypass the live 300 ms throttle, but duplicate timestamps are rejected and two-observation confirmation rules still apply. Quality weighting remains based on OCR confidence and numeric text size, not an automatic photo bonus.
- Disable the shutter during capture and recognition. Finish any in-flight live OCR first, then prioritize photo capture and recognition before accepting more live frames; the preview stays active. Show completion, empty-text, or failure feedback, then allow another shot. Images are processed in memory and released, not saved to a gallery.
- Bind preview, analysis, and still capture together; explicitly route all three outputs to the selected physical lens. CameraX negotiates supported resolutions for that combination. Switching rear lens preserves accumulated results.
- **New scan** clears both pipelines and rejects results from photos requested before the reset. Higher resolution may improve small-text recognition, but focus, motion, glare, and label curvature still affect accuracy; verify on real labels.

### Gemini App Handoff

- The separate **Gemini** shutter copies the extraction prompt to the clipboard immediately on a tap, then captures a full-resolution JPEG using CameraX's file output, including its rotation metadata. Ordinary photo OCR and live scanning are unchanged.
- Open Gemini directly after capture, without an app confirmation dialog. Share a temporary cached photo and the default extraction prompt through `ACTION_SEND`, with a narrowly scoped FileProvider URI and temporary read permission. Target `com.google.android.apps.bard`; fall back to the Android chooser if that target is unavailable. No API key or local Nano dependency is required, and minSdk stays 24.
- This is not local-only inference: Gemini may upload the photo. There is no result callback. Some receiving app versions may ignore the text accompanying an image; **Copy prompt** provides a manual fallback.
- The prompt requests `{"columns":[{"basis":"per 100 g","nutrients":[{"name":"fat","amount":8.2,"unit":"g"}]}],"uncertain":[]}`. Keep printed serving columns, units, comparisons, and uncertainty; do not calculate or guess missing readings.
- **Paste and import** reads the clipboard only on a tap. A text field and **Import** also accept manually pasted replies. Find the first valid nutrition object within prose or Markdown fences; validate the expected structure, nutrient names, amounts, and units. Malformed replies show an error without replacing the last successful import.
- **Import** and **Paste and import** populate the six scanner fields from a single column or the unique per-100-g column. Multi-column replies without a unique per-100-g match offer **Use this column**. Units are normalized to grams; comparisons remain comparisons. Conflicting duplicate nutrient entries remain ambiguous instead of being guessed.
- Imported fields show **Gemini**, count as ready when unambiguous, and feed **Review and fill draft**. They are not confirmed until the user confirms the review snapshot. Manual corrections take precedence; live OCR cannot overwrite Gemini values. Choosing another column replaces the previous Gemini field set. **Clear** removes both the manual and Gemini value for that field, restoring OCR. Pasted replies and imported values survive activity recreation; **New scan** clears them.
- Cancelled or failed captures are deleted. Shared photos remain available while the receiving app reads them; later captures remove cached files older than 24 hours. They are not saved to the gallery.

### Stateful Scanning

- The main scanner shows a fixed checklist for fat, saturated fat, carbohydrates, sugars, protein, and salt. Each row shows its retained value and a status: **Not found**, **Collecting**, **Multiple columns**, **Stable**, **Gemini**, or **Manual**. The progress header stays visible when scrolling and shows a checkmark with **All 6 fields ready** when all six are stable, unambiguous Gemini imports, or manually entered. Ready does not mean user-confirmed. Energy and fibre remain available in diagnostics but are not required for completion.
- Each required field has an edit action for manual gram values, including zero, decimal commas and printed comparisons. A single OCR value prefills the editor, converting mg to g; multiple columns do not prefill a guessed value. **Save value** creates a separate manual override that OCR cannot overwrite. **Clear** discards the draft and removes that override, returning to OCR; **Cancel** leaves the saved value unchanged. Manual values and open drafts survive activity recreation, and **New scan** clears overrides. Manual entry is also available without camera permission. It does not establish the serving basis or submit anything to NutriData.
- A stable value needs one detected column, at least three accepted matching observations with OCR confidence at least 0.85, and at least 75% of the recent quality/recency-weighted support. Numeric formatting differences are normalized. Live throttling and duplicate-photo rejection still apply. Conflicting readings can withdraw stability; values leaving the camera view keep their status until new evidence or **New scan**. These are heuristic OCR thresholds, not user verification or calibrated probabilities, and they do not establish a per-100-g serving basis.
- **Gemini response** and **Scan diagnostics** are separate, collapsed-by-default sections. Diagnostics contain raw OCR, all extracted nutrients, confident-number candidates, and energy hypotheses. Expanding/collapsing either section does not reset the scan. Gemini imports have their own provenance and do not contribute observations to OCR stability.
- Retain each nutrient as the camera moves around the product, even when that field leaves view or becomes unreadable.
- Keep up to 12 observations per nutrient, accepting live samples at most every 300 ms and each distinct photo separately. Recent observations, ML Kit confidence, and larger numeric text receive more weight. Text size is only a bounded quality hint, not proof that zoom improved accuracy.
- Show the first reading immediately. Replace it only when another value has at least two supporting observations and a weighted score more than 25% higher. Normalize equivalent decimal formatting, such as `8` and `8.0`.
- Keep the session through rotation, but not process death. Use **New scan** when moving to another product; it clears the history and rejects already-processing frames from before the reset.
- Nutrient aliases cover common inflected forms in Estonian, English, Latvian, Finnish, Lithuanian, German and Polish. Matching ignores case and accents, including missing umlauts, and accepts selected German `ae` spellings. Longer names (at least six characters) allow one inserted, missing or substituted character using a bounded Apache Commons Text edit distance. Missing spaces and hyphenated phrases can match too; short names, numbers and units remain exact. Ambiguous fuzzy matches are not guessed. English unsaturated/trans-fat phrases are excluded from total-fat matching.
- Results remain heuristic. Parsing still needs names and values on the same OCR line; wrapped phrases such as `kullastunud` on one line and `rasvhapped 2 g` on the next are not joined. Multiple values stay grouped, without matching serving columns across views or assuming a per-100-g basis.

### Filling The New-Food Draft

- In the app WebView, open **Lisa oma toiduaine**, choose **Vota aluseks sarnane toit**, and leave the package-data table open before tapping **Scan food**. The VS Code browser is only an inspection surface; its login and draft are not shared with the Android WebView.
- **Review and fill draft** freezes the six effective values. **Confirm and fill** explicitly confirms that snapshot, returns it through an activity result and fills the original draft. Values are assumed to be **per 100 g**, as requested; there is no serving conversion step. Milligrams are converted to grams and values use three decimal places.
- Missing values, multiple columns, comparisons such as `<0.5`, and contradictions between confirmed values require correction. A comparison needs a user-entered numeric estimate. Stable OCR and Gemini imports alone are not confirmation. The review uses manual corrections first, then Gemini imports, then OCR, and preserves that provenance in the transfer payload.
- Confirmed fat, saturated fat, available carbohydrates, sugars, protein and salt stay fixed. Salt sets sodium; inherited polyols are capped to the carbohydrate remainder and starch is recalculated. Inherited fatty-acid and sugar subtypes are reduced proportionally when necessary. Fibre and alcohol are capped to the remaining mass. Other inherited parent/child conflicts are reconciled, and energy is recalculated by NutriData. These adjustments are estimates, not new label evidence.
- The adapter uses the currently inspected Angular editor's nutrient IDs and internal calculation methods, including hidden rows. It batches reconciled values, sends input/change events for the six visible fields, runs the site's calculation and comparison checks, then reads back both model and visible label values. A failure restores the original nutrient values. The result lists adjusted inherited values and calculated energy.
- Filling is restricted to the same in-memory `add`/`copy` editor, base food, form state and nutrient snapshot captured before scanning. Existing-food editors, changed drafts, page reloads and wrong origins are rejected. Activity/WebView recreation loses the page marker and therefore requires scanning again. No save, submit, recipe API or diary-portion action is invoked.
- The adapter depends on the current website's private Angular shape. A site update can disable filling until the adapter is updated; it must not guess a different target. Android end-to-end authenticated filling remains to be verified by the user. A detached copy of the production editor accepted a representative reconciled label with no nutrient errors and no live-draft changes.
- Focused offline regression check: `node app/src/test/js/nutridata-draft.test.cjs`. Covers fixed values, proportional reduction, zeroes, conflicts, stale drafts, existing-food rejection, wrong origins, read-back failure and rollback. No device installation is part of this check.

### Independent Numeric Fallback

- Collect individual OCR numbers with confidence at least 0.85, even when surrounding label text is unreliable. Show them immediately; require two accepted sightings before using them in hypotheses. Throttle live sightings to 300 ms; each distinct photo can contribute separately. This threshold is an OCR score, not a calibrated probability of correctness.
- Retain up to 24 numbers across the scan and search up to 12 candidates per role. Ignore obvious percentages, comparators, and long barcode-like tokens. Keep known units; bare numbers remain untyped observations.
- Independently compare observed combinations with `kcal ~= 9 * fat + 4 * (carbohydrates + protein)`, adding `2 * fibre` when fibre is already labelled. Accept a tolerance of 5 kcal or 5%, whichever is larger, and show at most three closest matches. Convert explicit kJ using 4.184 kJ/kcal; without an energy unit, a bare number can only be a tentative calorie candidate.
- Use labelled readings as constraints, never overwrite them with guesses. Do not reuse a single number for multiple nutrient roles unless separate occurrences were seen. Carbs and protein are interchangeable in the energy equation, so show that ambiguity unless a label resolves it.
- These are unverified hypotheses, not nutrient identifications. They assume one serving basis; known multi-column macro readings disable inference. Unlabelled columns can still mix, and rounding, polyols, alcohol, or unobserved fibre can invalidate the equation. The fallback cannot recover digits the OCR never reads.
- Keep both mechanisms in the same in-memory session, surviving rotation. **New scan** clears both. Cache unchanged hypotheses to avoid repeating the search on every camera frame.

## Proposed Approach

The app wraps the existing Nutridata web interface in a WebView, with native Android features to enhance the experience. Public-page loading is verified; compatibility with the authenticated food-entry flow still needs to be confirmed.

## Planned Product Scope

- Log in to Nutridata and use its web interface within the app.
- Use the Android camera to capture food product labels.
- Extract label text with OCR to assist with entering product information.
- Let users review and correct the extracted information before using it to add a product to their personal database.

## Intended Flow

1. Log in to Nutridata.
2. Start adding a new food product.
3. Capture the product label with the camera.
4. Review and correct the OCR results.
5. Confirm the six per-100-g values to fill and reconcile the original new-food draft.
6. Review the resulting website form, set the product name, and save manually when ready.

## Possible Later Features

Assist users with logging daily food consumption. This is outside the planned product scope above, which focuses on adding new food products to the personal database.