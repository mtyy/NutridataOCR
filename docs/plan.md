# Nutridata OCR App Plan

## Goal

Create an Android app that lets users log in to [tap.nutridata.ee](https://tap.nutridata.ee) and use their phone's camera and OCR to make adding new food products to their personal database easier.

## Prototype Stage

This is a prototype: a Compose camera screen with raw OCR text and live nutrient extraction for English, Estonian, Latvian, Finnish, Lithuanian, German, and Polish. Russian is deferred. Login, WebView integration, and saving products are not implemented yet.

Keep automated tests minimal: add them only when needed to move to the next step. Use a build check for compilation and rely on the user to verify camera behavior and OCR accuracy with real labels on an Android device.

### Stateful Scanning

- Retain each nutrient as the camera moves around the product, even when that field leaves view or becomes unreadable.
- Keep up to 12 observations per nutrient, accepting at most one every 300 ms. Recent observations, ML Kit confidence, and larger numeric text receive more weight. Text size is only a bounded quality hint, not proof that zoom improved accuracy.
- Show the first reading immediately. Replace it only when another value has at least two supporting observations and a weighted score more than 25% higher. Normalize equivalent decimal formatting, such as `8` and `8.0`.
- Keep the session through rotation, but not process death. Use **New scan** when moving to another product; it clears the history and rejects already-processing frames from before the reset.
- Results remain heuristic. Parsing still needs names and values on the same OCR line; multiple values stay grouped, without matching serving columns across views or assuming a per-100-g basis.

### Independent Numeric Fallback

- Collect individual OCR numbers with confidence at least 0.85, even when surrounding label text is unreliable. Show them immediately; require two sightings at least 300 ms apart before using them in hypotheses. This threshold is an OCR score, not a calibrated probability of correctness.
- Retain up to 24 numbers across the scan and search up to 12 candidates per role. Ignore obvious percentages, comparators, and long barcode-like tokens. Keep known units; bare numbers remain untyped observations.
- Independently compare observed combinations with `kcal ~= 9 * fat + 4 * (carbohydrates + protein)`, adding `2 * fibre` when fibre is already labelled. Accept a tolerance of 5 kcal or 5%, whichever is larger, and show at most three closest matches. Convert explicit kJ using 4.184 kJ/kcal; without an energy unit, a bare number can only be a tentative calorie candidate.
- Use labelled readings as constraints, never overwrite them with guesses. Do not reuse a single number for multiple nutrient roles unless separate occurrences were seen. Carbs and protein are interchangeable in the energy equation, so show that ambiguity unless a label resolves it.
- These are unverified hypotheses, not nutrient identifications. They assume one serving basis; known multi-column macro readings disable inference. Unlabelled columns can still mix, and rounding, polyols, alcohol, or unobserved fibre can invalidate the equation. The fallback cannot recover digits the OCR never reads.
- Keep both mechanisms in the same in-memory session, surviving rotation. **New scan** clears both. Cache unchanged hypotheses to avoid repeating the search on every camera frame.

## Proposed Approach

The app will likely be a WebView wrapper around the existing Nutridata web interface, with native Android features to enhance the experience. WebView suitability, including compatibility with the site's login flow, still needs to be confirmed.

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
5. Use the reviewed information to complete and save the product in Nutridata.

The mechanism for transferring OCR results into the product form remains to be decided.

## Possible Later Features

Assist users with logging daily food consumption. This is outside the planned product scope above, which focuses on adding new food products to the personal database.