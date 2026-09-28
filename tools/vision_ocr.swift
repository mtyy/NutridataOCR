// Local macOS Vision OCR: prints one JSON object per image with recognized lines and normalized boxes.
import Foundation
import Vision

for path in CommandLine.arguments.dropFirst() {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = false
    request.recognitionLanguages = ["et-EE", "en-US", "lv-LV", "lt-LT", "fi-FI", "ru-RU", "de-DE", "pl-PL"]
    try VNImageRequestHandler(url: URL(fileURLWithPath: path)).perform([request])
    let lines: [[String: Any]] = (request.results ?? []).compactMap { observation in
        guard let candidate = observation.topCandidates(1).first else { return nil }
        let box = observation.boundingBox
        return [
            "text": candidate.string,
            "confidence": candidate.confidence,
            "x": box.minX, "y": 1 - box.maxY, "w": box.width, "h": box.height,
        ]
    }
    let result: [String: Any] = ["image": (path as NSString).lastPathComponent, "lines": lines]
    let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
    print(String(data: data, encoding: .utf8)!)
}
