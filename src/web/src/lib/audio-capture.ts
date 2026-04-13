export interface BrowserAudioChunk {
  encoding: "pcm16le";
  sampleRateHz: number;
  channelCount: number;
  durationMs: number;
  dataBase64: string;
}

const PROCESSOR_BUFFER_SIZE = 4096;

function encodeFloat32ToPcm16Base64(samples: Float32Array): string {
  const pcm = new Int16Array(samples.length);
  for (let index = 0; index < samples.length; index += 1) {
    const clamped = Math.max(-1, Math.min(1, samples[index] ?? 0));
    pcm[index] = clamped < 0 ? clamped * 32768 : clamped * 32767;
  }

  const bytes = new Uint8Array(pcm.buffer);
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    const slice = bytes.subarray(offset, offset + chunkSize);
    binary += String.fromCharCode(...slice);
  }
  return btoa(binary);
}

export class BrowserAudioCapture {
  private audioContext: AudioContext | null = null;
  private mediaStream: MediaStream | null = null;
  private sourceNode: MediaStreamAudioSourceNode | null = null;
  private processorNode: ScriptProcessorNode | null = null;

  async start(onChunk: (chunk: BrowserAudioChunk) => void): Promise<void> {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error("This browser does not support microphone capture.");
    }

    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    });

    this.audioContext = new AudioContext({ latencyHint: "interactive" });
    this.sourceNode = this.audioContext.createMediaStreamSource(this.mediaStream);
    this.processorNode = this.audioContext.createScriptProcessor(PROCESSOR_BUFFER_SIZE, 1, 1);
    this.processorNode.onaudioprocess = (event) => {
      const audioContext = this.audioContext;
      if (!audioContext) {
        return;
      }

      const inputSamples = event.inputBuffer.getChannelData(0);
      const copiedSamples = new Float32Array(inputSamples.length);
      copiedSamples.set(inputSamples);
      onChunk({
        encoding: "pcm16le",
        sampleRateHz: audioContext.sampleRate,
        channelCount: 1,
        durationMs: Math.round((copiedSamples.length * 1000) / audioContext.sampleRate),
        dataBase64: encodeFloat32ToPcm16Base64(copiedSamples),
      });
    };

    this.sourceNode.connect(this.processorNode);
    this.processorNode.connect(this.audioContext.destination);
  }

  async stop(): Promise<void> {
    this.processorNode?.disconnect();
    this.sourceNode?.disconnect();
    this.processorNode = null;
    this.sourceNode = null;

    this.mediaStream?.getTracks().forEach((track) => track.stop());
    this.mediaStream = null;

    if (this.audioContext !== null) {
      await this.audioContext.close();
      this.audioContext = null;
    }
  }
}