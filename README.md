# Nutridata OCR

A convenience wrapper around tap.nutridata.ee website.

## OCR food entering
* Point your camera to use Optical Character Recognition (OCR) for finding nutritional values from the label and to prefill the fields on the website.
* When basing on existing foods, it also automatically recalculates the micronutrients composition (like all different fats) so it passes the website checks. This saves the hassle of figuring it out manually.

## Convenience
* When adding new foods based on existing foods it prefills the search query with your previous search query (as that's the likely food you are about to add)

## No LLMs
It uses an OCR model under the hood, which is faster and also runs on older phones. All processing is local.
However, since it is less accurate, there are a bunch of heuristics to help it out.

## Ok, some LLMs
For very tricky cases, you can press gemini button, this does:

1. Copy a prompt for gemini to clipboard
2. Launch gemini app with current photo frame

Now you need to paste the prompt, and let gemini figure out the nutrients. Gemini will output json which you can just paste back to app for processing.
Note this means you share your photos with google.
