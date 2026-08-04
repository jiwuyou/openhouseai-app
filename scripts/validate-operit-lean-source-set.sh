#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
main_java="$repo_dir/operit-feature/src/main/java"
main_assets="$repo_dir/operit-feature/src/main/assets"
main_manifest="$repo_dir/operit-feature/src/main/AndroidManifest.xml"
feature_gradle="$repo_dir/operit-feature/build.gradle"
cmake_file="$repo_dir/operit-feature/src/main/cpp/CMakeLists.txt"
compile_log="${1:-}"
baseline_lines="${OPERIT_LEAN_BASELINE_LINES:-397401}"
max_main_lines=$((baseline_lines * 75 / 100))
failed=0

check_absent() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  if matches="$(rg -n --hidden "$pattern" "$@" 2>/dev/null)"; then
    printf 'error: %s\n%s\n' "$label" "$matches" >&2
    failed=1
  fi
}

check_present() {
  local label="$1"
  local pattern="$2"
  local path="$3"
  if ! rg -q "$pattern" "$path"; then
    printf 'error: %s\n' "$label" >&2
    failed=1
  fi
}

[[ -d "$main_java" && -f "$main_manifest" && -f "$feature_gradle" ]] \
  || { printf 'error: Operit lean source-set inputs are missing\n' >&2; exit 2; }

check_absent \
  'optional feature implementations remain in src/main' \
  'Mp4Renderer|FloatingScreenOcrScreen|OpenAIRealtimeVoiceProvider|DeepgramSttProvider|FloatingFullscreenScreen|StandardMusicPlaybackTools|MarkdownVideoRenderer|MarkdownAudioRenderer|OCRUtils|FFmpegUtil' \
  "$main_java"

check_absent \
  'src/main still imports an excluded runtime dependency' \
  '^import (com\.google\.android\.exoplayer2|com\.arthenica\.ffmpegkit|org\.apache\.poi|com\.tom_roush\.pdfbox|com\.itextpdf|rikka\.shizuku|com\.joaomgcd\.taskerpluginlibrary|org\.vosk|com\.microsoft\.onnxruntime)' \
  "$main_java"

for excluded_asset in packages/ffmpeg.js packages/file_converter.js; do
  if [[ -e "$main_assets/$excluded_asset" ]]; then
    printf 'error: src/main still packages excluded asset %s\n' "$excluded_asset" >&2
    failed=1
  fi
done

check_absent \
  'lean Manifest still declares an excluded Operit service or permission' \
  'FloatingChatService|OperitVoiceInteractionService|VoiceInteractionSessionService|NotificationListener|VoiceAssistantWidget|android\.permission\.RECORD_AUDIO|android\.permission\.FOREGROUND_SERVICE_MICROPHONE|android\.permission\.BIND_VOICE_INTERACTION' \
  "$main_manifest"

check_absent \
  'operit-feature still declares an excluded dependency' \
  'exoplayer|ffmpeg|org\.apache\.poi|pdfbox|itext|junrar|shizuku|taskerplugin|glide|android-gif-drawable|sherpa|ncnn|onnxruntime|mediapipe|tensorflow-lite' \
  "$feature_gradle"

check_absent \
  'lean CMake still references an excluded native feature' \
  'ocr|ffmpeg|avatar|sherpa|ncnn|mnn|onnx|mediapipe|tensorflow|filament' \
  "$cmake_file"

check_absent \
  'pure Kotlin validation still depends on the Rust payload build' \
  'tasks\.named\("preBuild"\).*buildSharedPiRustAndroid' \
  "$feature_gradle"
check_present \
  'Rust payload build is not attached to JNI merge tasks' \
  'merge\.\*JniLibFolders' \
  "$feature_gradle"

if [[ -n "$compile_log" ]]; then
  [[ -f "$compile_log" ]] || { printf 'error: compile log not found: %s\n' "$compile_log" >&2; exit 2; }
  check_absent \
    'compile log includes an excluded representative class' \
    'Mp4Renderer|FloatingScreenOcrScreen|OpenAIRealtimeVoiceProvider|DeepgramSttProvider|FloatingFullscreenScreen|StandardMusicPlaybackTools' \
    "$compile_log"
fi

main_files="$(find "$main_java" -type f \( -name '*.kt' -o -name '*.java' \) | wc -l)"
main_lines="$(find "$main_java" -type f \( -name '*.kt' -o -name '*.java' \) -print0 \
  | xargs -0 wc -l | awk 'END {print $1}')"
optional_files="$(find "$repo_dir/operit-feature/src/optional/java" -type f \
  \( -name '*.kt' -o -name '*.java' \) | wc -l)"

if (( main_lines > max_main_lines )); then
  printf 'error: src/main has %s lines; the 25%% reduction gate allows at most %s\n' \
    "$main_lines" "$max_main_lines" >&2
  failed=1
fi

(( failed == 0 )) || exit 1

reduction="$(awk -v before="$baseline_lines" -v after="$main_lines" \
  'BEGIN { printf "%.1f", (before - after) * 100 / before }')"
printf 'Operit lean source-set gate passed\n'
printf 'main: %s files, %s lines (%s%% reduction from %s)\n' \
  "$main_files" "$main_lines" "$reduction" "$baseline_lines"
printf 'optional: %s source files\n' "$optional_files"
