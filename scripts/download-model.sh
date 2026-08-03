#!/bin/bash
# ───────────────────────────────────────────────────────────
# Modell-Download-Script für Sherpa Transcript
# Lädt das Kroko Zipformer-Transducer-Modell (Deutsch) herunter
# und platziert es im app/src/main/assets/models/sherpa/ Verzeichnis.
# ───────────────────────────────────────────────────────────
set -euo pipefail

MODEL_DIR="app/src/main/assets/models/sherpa/kroko-de"
MODEL_REPO="csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06"
FILES=("encoder.onnx" "decoder.onnx" "joiner.onnx" "tokens.txt")

echo "=== Sherpa Transcript — Modell-Download ==="
echo "Modell: $MODEL_REPO"
echo "Ziel:   $MODEL_DIR"
echo ""

mkdir -p "$MODEL_DIR"

for FILE in "${FILES[@]}"; do
    TARGET="$MODEL_DIR/$FILE"
    if [[ -f "$TARGET" && -s "$TARGET" ]]; then
        echo "✓ $FILE bereits vorhanden ($(du -h "$TARGET" | cut -f1))"
        continue
    fi

    URL="https://huggingface.co/${MODEL_REPO}/resolve/main/${FILE}"
    echo "↓ Lade $FILE herunter..."
    
    if command -v wget &>/dev/null; then
        wget -q --show-progress "$URL" -O "$TARGET"
    elif command -v curl &>/dev/null; then
        curl -L# "$URL" -o "$TARGET"
    else
        echo "❌ Weder wget noch curl gefunden."
        exit 1
    fi

    if [[ -f "$TARGET" ]]; then
        echo "  ✓ $(du -h "$TARGET" | cut -f1)"
    else
        echo "❌ Download fehlgeschlagen: $FILE"
        exit 1
    fi
done

echo ""
echo "✓ Modell-Download abgeschlossen."
echo ""
echo "Dateien:"
ls -lh "$MODEL_DIR"
echo ""
echo "Das Modell wird beim nächsten App-Start automatisch aus den Assets geladen."
