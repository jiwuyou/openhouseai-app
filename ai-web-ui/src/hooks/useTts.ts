"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { AssistantTtsConfig, TtsClientInstruction } from "@/lib/wuxianpi/contracts";
import { speakText } from "@/components/wuxianpi/api";

function cleanForSpeech(text: string, readCode: boolean): string {
  let next = text;
  if (!readCode) next = next.replace(/```[\s\S]*?```/g, "（代码块已省略）");
  return next.replace(/`([^`]+)`/g, "$1").replace(/!\[[^\]]*]\([^)]*\)/g, "").replace(/\[([^\]]+)]\([^)]*\)/g, "$1").replace(/^#{1,6}\s+/gm, "").replace(/[>*_~|]/g, " ").replace(/\s+/g, " ").trim();
}

function abortError(): DOMException {
  return new DOMException("语音已取消", "AbortError");
}

function speakInBrowser(instruction: TtsClientInstruction, signal: AbortSignal): Promise<void> {
  if (!("speechSynthesis" in window)) return Promise.reject(new Error("当前浏览器不支持语音朗读"));
  return new Promise((resolve, reject) => {
    const utterance = new SpeechSynthesisUtterance(instruction.text);
    let settled = false;
    const finish = (reason?: unknown) => {
      if (settled) return;
      settled = true;
      signal.removeEventListener("abort", abort);
      if (reason) reject(reason); else resolve();
    };
    const abort = () => { window.speechSynthesis.cancel(); finish(abortError()); };
    utterance.rate = instruction.rate ?? 1;
    utterance.pitch = instruction.pitch ?? 1;
    if (instruction.voice) {
      const voice = window.speechSynthesis.getVoices().find((item) => item.name === instruction.voice || item.voiceURI === instruction.voice);
      if (voice) utterance.voice = voice;
    }
    utterance.onend = () => finish();
    utterance.onerror = (event) => finish(signal.aborted ? abortError() : new Error(event.error || "语音朗读失败"));
    if (signal.aborted) return abort();
    signal.addEventListener("abort", abort, { once: true });
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
  });
}

export function useTts(assistantId?: string, config?: AssistantTtsConfig) {
  const [speaking, setSpeaking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const audioUrlRef = useRef<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);

  const clearAudio = useCallback(() => {
    const audio = audioRef.current;
    if (audio) {
      audio.pause();
      audio.removeAttribute("src");
      audio.load();
    }
    audioRef.current = null;
    if (audioUrlRef.current) URL.revokeObjectURL(audioUrlRef.current);
    audioUrlRef.current = null;
  }, []);

  const stop = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    if (typeof window !== "undefined" && "speechSynthesis" in window) window.speechSynthesis.cancel();
    clearAudio();
    setSpeaking(false);
  }, [clearAudio]);

  const playBlob = useCallback((blob: Blob, signal: AbortSignal): Promise<void> => {
    clearAudio();
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    audioRef.current = audio;
    audioUrlRef.current = url;
    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (reason?: unknown) => {
        if (settled) return;
        settled = true;
        signal.removeEventListener("abort", abort);
        audio.onended = null;
        audio.onerror = null;
        clearAudio();
        if (reason) reject(reason); else resolve();
      };
      const abort = () => finish(abortError());
      audio.onended = () => finish();
      audio.onerror = () => finish(new Error("音频播放失败"));
      if (signal.aborted) return abort();
      signal.addEventListener("abort", abort, { once: true });
      void audio.play().catch(finish);
    });
  }, [clearAudio]);

  const speak = useCallback(async (rawText: string, preview = false) => {
    const text = cleanForSpeech(rawText, config?.readCode ?? false);
    if (!text) return;
    stop();
    const controller = new AbortController();
    controllerRef.current = controller;
    setSpeaking(true);
    setError(null);
    try {
      let output: TtsClientInstruction | Blob | null;
      try {
        output = await speakText({ profileId: config?.profileId ?? "browser-default", assistantId, text, rate: config?.rate, pitch: config?.pitch, readCode: config?.readCode, preview }, controller.signal);
      } catch {
        if (controller.signal.aborted) throw abortError();
        output = { kind: "browser-speech", text, rate: config?.rate, pitch: config?.pitch };
      }
      if (controller.signal.aborted) throw abortError();
      if (output instanceof Blob) await playBlob(output, controller.signal);
      else if (output) await speakInBrowser(output, controller.signal);
    } catch (reason) {
      if (!(reason instanceof DOMException && reason.name === "AbortError")) setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null;
        setSpeaking(false);
      }
    }
  }, [assistantId, config?.pitch, config?.profileId, config?.rate, config?.readCode, playBlob, stop]);

  useEffect(() => stop, [stop]);
  return { speak, stop, speaking, error };
}
