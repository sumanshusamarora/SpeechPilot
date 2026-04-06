from __future__ import annotations

import asyncio
import base64
import logging
from dataclasses import dataclass, field
from typing import Callable, Protocol

import numpy as np
from faster_whisper import WhisperModel

from speechpilot_contracts.events import (
    AudioChunkPayload,
    ServerEvent,
    TranscriptFinalEvent,
    TranscriptFinalPayload,
    TranscriptPartialEvent,
    TranscriptPartialPayload,
    TranscriptSegmentPayload,
)

from app.config.settings import Settings
from app.domain.session import SessionContext
from app.domain.transcript import count_words

TranscribeCallable = Callable[[np.ndarray], str]


@dataclass(slots=True)
class StreamingSessionState:
    sample_chunks: list[np.ndarray] = field(default_factory=list)
    sample_count: int = 0
    last_partial_sample_count: int = 0
    silence_duration_ms: int = 0
    partial_sequence: int = 0
    utterance_index: int = 0
    session_elapsed_ms: int = 0
    segment_start_ms: int | None = None
    last_partial_text: str = ""


class SpeechToTextProviderError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        detail: str | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.detail = detail


class SpeechToTextProvider(Protocol):
    @property
    def provider_name(self) -> str: ...

    async def start_session(self, session: SessionContext) -> None: ...

    async def on_audio_chunk(
        self,
        session: SessionContext,
        chunk: AudioChunkPayload,
    ) -> list[ServerEvent]: ...

    async def finish_session(self, session: SessionContext) -> list[ServerEvent]: ...


def _resample_audio(samples: np.ndarray, source_rate_hz: int, target_rate_hz: int) -> np.ndarray:
    if samples.size == 0 or source_rate_hz == target_rate_hz:
        return samples.astype(np.float32, copy=False)

    source_positions = np.linspace(0.0, 1.0, num=samples.shape[0], endpoint=False)
    target_length = max(1, int(round(samples.shape[0] * target_rate_hz / source_rate_hz)))
    target_positions = np.linspace(0.0, 1.0, num=target_length, endpoint=False)
    resampled = np.interp(target_positions, source_positions, samples)
    return resampled.astype(np.float32, copy=False)


class FasterWhisperSpeechToTextProvider:
    def __init__(
        self,
        settings: Settings,
        logger: logging.Logger,
        transcribe_callable: TranscribeCallable | None = None,
    ) -> None:
        self._logger = logger
        self._language = settings.stt_language
        self._target_sample_rate_hz = settings.stt_target_sample_rate_hz
        self._partial_interval_ms = settings.stt_partial_interval_ms
        self._silence_duration_ms = settings.stt_silence_duration_ms
        self._min_utterance_ms = settings.stt_min_utterance_ms
        self._speech_threshold = settings.stt_speech_threshold
        self._model_size = settings.stt_model_size
        self._device = settings.stt_device
        self._compute_type = settings.stt_compute_type
        self._transcribe_callable = transcribe_callable
        self._model: WhisperModel | None = None
        self._model_lock = asyncio.Lock()
        self._sessions: dict[str, StreamingSessionState] = {}

    @property
    def provider_name(self) -> str:
        return "faster_whisper"

    async def start_session(self, session: SessionContext) -> None:
        self._sessions[session.session_id] = StreamingSessionState()

    async def on_audio_chunk(
        self,
        session: SessionContext,
        chunk: AudioChunkPayload,
    ) -> list[ServerEvent]:
        state = self._sessions.get(session.session_id)
        if state is None:
            raise SpeechToTextProviderError(
                code="provider_session_missing",
                message="The transcription provider session was not initialized.",
                retryable=False,
                detail=session.session_id,
            )

        samples = self._decode_chunk(chunk)
        if samples.size == 0:
            return []

        rms = float(np.sqrt(np.mean(np.square(samples), dtype=np.float64)))
        chunk_duration_ms = int(round(samples.shape[0] * 1000 / self._target_sample_rate_hz))
        chunk_start_ms = state.session_elapsed_ms
        state.session_elapsed_ms += chunk_duration_ms
        if rms < self._speech_threshold and state.sample_count == 0:
            return []

        if state.sample_count == 0 and state.segment_start_ms is None:
            state.segment_start_ms = chunk_start_ms

        state.sample_chunks.append(samples)
        state.sample_count += samples.shape[0]
        if rms < self._speech_threshold:
            state.silence_duration_ms += chunk_duration_ms
        else:
            state.silence_duration_ms = 0

        events: list[ServerEvent] = []
        if self._should_emit_partial(state):
            partial_text = await self._transcribe_state(state)
            if partial_text and partial_text != state.last_partial_text:
                state.partial_sequence += 1
                state.last_partial_text = partial_text
                events.append(
                    TranscriptPartialEvent(
                        payload=TranscriptPartialPayload(
                            sessionId=session.session_id,
                            text=partial_text,
                            sequence=state.partial_sequence,
                        )
                    )
                )
            state.last_partial_sample_count = state.sample_count

        if self._should_finalize(state):
            final_text = await self._transcribe_state(state)
            if final_text:
                state.utterance_index += 1
                segment_end_ms = max(state.segment_start_ms or 0, state.session_elapsed_ms - state.silence_duration_ms)
                events.append(
                    TranscriptFinalEvent(
                        payload=TranscriptFinalPayload(
                            sessionId=session.session_id,
                            segment=TranscriptSegmentPayload(
                                id=f"{session.session_id}:{state.utterance_index}",
                                text=final_text,
                                startTimeMs=state.segment_start_ms or 0,
                                endTimeMs=segment_end_ms,
                                wordCount=count_words(final_text),
                            ),
                        )
                    )
                )
            self._reset_state(state)

        return events

    async def finish_session(self, session: SessionContext) -> list[ServerEvent]:
        state = self._sessions.pop(session.session_id, None)
        if state is None or state.sample_count == 0:
            return []

        final_text = await self._transcribe_state(state)
        if not final_text:
            return []

        state.utterance_index += 1
        return [
            TranscriptFinalEvent(
                payload=TranscriptFinalPayload(
                    sessionId=session.session_id,
                    segment=TranscriptSegmentPayload(
                        id=f"{session.session_id}:{state.utterance_index}",
                        text=final_text,
                        startTimeMs=state.segment_start_ms or max(0, state.session_elapsed_ms - int(round(state.sample_count * 1000 / self._target_sample_rate_hz))),
                        endTimeMs=state.session_elapsed_ms,
                        wordCount=count_words(final_text),
                    ),
                )
            )
        ]

    def _decode_chunk(self, chunk: AudioChunkPayload) -> np.ndarray:
        if chunk.dataBase64 is None:
            raise SpeechToTextProviderError(
                code="audio_chunk_missing_data",
                message="Audio chunk payload did not include base64 audio data.",
                retryable=False,
            )
        if chunk.encoding != "pcm16le":
            raise SpeechToTextProviderError(
                code="audio_chunk_unsupported_encoding",
                message="Only pcm16le audio chunks are supported in this vertical slice.",
                retryable=False,
                detail=chunk.encoding,
            )

        raw_audio = base64.b64decode(chunk.dataBase64)
        if len(raw_audio) % 2 != 0:
            raise SpeechToTextProviderError(
                code="audio_chunk_invalid_size",
                message="PCM chunk length must be divisible by 2 bytes.",
                retryable=False,
            )

        pcm_samples = np.frombuffer(raw_audio, dtype="<i2")
        if chunk.channelCount > 1:
            frame_count = pcm_samples.shape[0] // chunk.channelCount
            pcm_samples = pcm_samples[: frame_count * chunk.channelCount]
            pcm_samples = pcm_samples.reshape(frame_count, chunk.channelCount).mean(axis=1).astype(np.int16)

        normalized = pcm_samples.astype(np.float32) / 32768.0
        return _resample_audio(normalized, chunk.sampleRateHz, self._target_sample_rate_hz)

    def _should_emit_partial(self, state: StreamingSessionState) -> bool:
        if state.sample_count == 0:
            return False
        elapsed_ms = int(round((state.sample_count - state.last_partial_sample_count) * 1000 / self._target_sample_rate_hz))
        total_ms = int(round(state.sample_count * 1000 / self._target_sample_rate_hz))
        return total_ms >= self._min_utterance_ms and elapsed_ms >= self._partial_interval_ms

    def _should_finalize(self, state: StreamingSessionState) -> bool:
        total_ms = int(round(state.sample_count * 1000 / self._target_sample_rate_hz))
        return total_ms >= self._min_utterance_ms and state.silence_duration_ms >= self._silence_duration_ms

    async def _transcribe_state(self, state: StreamingSessionState) -> str:
        samples = np.concatenate(state.sample_chunks) if len(state.sample_chunks) > 1 else state.sample_chunks[0]
        if self._transcribe_callable is not None:
            return self._transcribe_callable(samples).strip()

        async with self._model_lock:
            model = await self._ensure_model()
            return await asyncio.to_thread(self._transcribe_sync, model, samples.copy())

    async def _ensure_model(self) -> WhisperModel:
        if self._model is None:
            self._logger.info(
                "loading faster-whisper model size=%s device=%s compute_type=%s",
                self._model_size,
                self._device,
                self._compute_type,
            )
            self._model = await asyncio.to_thread(
                WhisperModel,
                self._model_size,
                device=self._device,
                compute_type=self._compute_type,
            )
        return self._model

    def _transcribe_sync(self, model: WhisperModel, samples: np.ndarray) -> str:
        try:
            segments, _info = model.transcribe(
                samples,
                language=self._language,
                beam_size=1,
                best_of=1,
                condition_on_previous_text=False,
                without_timestamps=True,
                vad_filter=False,
            )
        except Exception as exc:
            raise SpeechToTextProviderError(
                code="stt_transcription_failed",
                message="The faster-whisper provider failed while transcribing audio.",
                retryable=True,
                detail=str(exc),
            ) from exc

        text_parts = [segment.text.strip() for segment in segments if segment.text.strip()]
        return " ".join(text_parts).strip()

    def _reset_state(self, state: StreamingSessionState) -> None:
        state.sample_chunks.clear()
        state.sample_count = 0
        state.last_partial_sample_count = 0
        state.silence_duration_ms = 0
        state.segment_start_ms = None
        state.last_partial_text = ""