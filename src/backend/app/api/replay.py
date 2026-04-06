from __future__ import annotations

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile

from speechpilot_contracts.events import dump_event

from app.providers.container import ServiceContainer

router = APIRouter(prefix="/api/replay", tags=["replay"])


@router.get("/capabilities")
async def replay_capabilities(request: Request) -> dict[str, object]:
    container: ServiceContainer = request.app.state.container
    return {
        "enabled": container.settings.replay_enabled,
        "status": "available",
        "accepted_inputs": ["wav_upload"],
        "notes": [
            "Replay uploads reuse the same backend session and transcription pipeline as live websocket audio.",
            "Replay currently expects 16-bit PCM WAV files.",
        ],
    }


@router.post("/transcribe")
async def transcribe_replay(
    request: Request,
    file: UploadFile = File(...),
    locale: str | None = Form(default=None),
) -> dict[str, object]:
    container: ServiceContainer = request.app.state.container
    if not container.settings.replay_enabled:
        raise HTTPException(status_code=404, detail="Replay mode is disabled.")

    if file.filename is None or not file.filename.lower().endswith(".wav"):
        raise HTTPException(status_code=400, detail="Replay currently accepts .wav files only.")

    audio_bytes = await file.read()
    try:
        replay_result = await container.session_service.run_replay(
            audio_bytes=audio_bytes,
            file_name=file.filename,
            locale=locale,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {
        "sessionId": replay_result.session_id,
        "provider": container.stt_provider.provider_name,
        "summary": replay_result.summary.model_dump(mode="json"),
        "events": [dump_event(event) for event in replay_result.transcript_events],
    }