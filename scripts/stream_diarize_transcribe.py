import sys
import json
import numpy as np
import pyaudio
import traceback
from diart import OnlineSpeakerDiarization, PipelineConfig
from faster_whisper import WhisperModel

# ----------------------------------------------------
# Configurations
# ----------------------------------------------------
SAMPLE_RATE = 16000
CHANNELS = 1
CHUNK_SIZE = 1600  # 100ms chunks

def main():
    # Model settings
    model_size = "small"  # Keep CPU/GPU footprint to minimum
    compute_type = "int8" # Optimized quantization for low footprint
    device = "cpu"        # Low-latency background processing
    
    # 1. Initialize Streaming Diarization Engine
    # Configure diart to utilize a small rolling window of 5.0s and slide step of 0.5s
    config = PipelineConfig(
        duration=5.0,
        step=0.5,
        latency="min"
    )
    diarization = OnlineSpeakerDiarization(config)
    
    # 2. Initialize Optimized Transcription Engine
    # Use faster-whisper with int8 quantization on CPU
    whisper_model = WhisperModel(model_size, device=device, compute_type=compute_type)
    
    # Audio buffer for faster-whisper
    # We maintain a rolling buffer in memory to avoid writing to disk
    rolling_audio_buffer = []
    max_rolling_chunks = int(30 * SAMPLE_RATE / CHUNK_SIZE) # limit to rolling 30 seconds
    
    # 3. Audio Ingestion Loop using PyAudio
    p = pyaudio.PyAudio()
    try:
        stream = p.open(
            format=pyaudio.paInt16,
            channels=CHANNELS,
            rate=SAMPLE_RATE,
            input=True,
            frames_per_buffer=CHUNK_SIZE,
            exception_on_overflow=False # Handle PyAudio overflow gracefully
        )
    except Exception as e:
        print(json.dumps({"status": "error", "message": f"Failed to open PyAudio stream: {e}"}))
        sys.exit(1)
        
    print(json.dumps({"status": "ready", "message": "Streaming engine initialized successfully."}))
    sys.stdout.flush()
    
    # Streaming state variables
    diarization_timeline = [] # list of tuples: (start, end, speaker)
    total_samples_read = 0
    
    try:
        while True:
            # 4. Ingest raw 16kHz audio chunk
            try:
                data = stream.read(CHUNK_SIZE, exception_on_overflow=False)
            except IOError as ex:
                # Handle overflow gracefully, skip this chunk
                continue
                
            # Convert bytes to float32 numpy array
            audio_data = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
            
            # Pipe straight to the diart pipeline to identify the active speaker
            # Diart OnlineSpeakerDiarization expects chunked audio input
            chunk_duration = len(audio_data) / SAMPLE_RATE
            start_time = total_samples_read / SAMPLE_RATE
            end_time = start_time + chunk_duration
            total_samples_read += len(audio_data)
            
            # Run diart pipeline step
            try:
                # Online diarization step prediction
                predictions = diarization(audio_data)
                # Parse predictions and update diarization_timeline
                for segment, _, speaker in predictions.itertracks(yield_label=True):
                    diarization_timeline.append((segment.start, segment.end, speaker))
            except Exception as diar_err:
                # Fail gracefully, continue transcription
                pass
                
            # Maintain rolling audio buffer in memory for faster-whisper transcription
            rolling_audio_buffer.extend(audio_data)
            if len(rolling_audio_buffer) > max_rolling_chunks * CHUNK_SIZE:
                # Shift buffer, dropping oldest audio chunks
                rolling_audio_buffer = rolling_audio_buffer[-max_rolling_chunks * CHUNK_SIZE:]
                
            # Run transcription periodically (e.g., when we detect a pause or every 2-3 seconds)
            if len(rolling_audio_buffer) >= int(2.0 * SAMPLE_RATE): # transcribe every 2.0s
                audio_np = np.array(rolling_audio_buffer)
                
                # Perform low-footprint transcription
                segments, info = whisper_model.transcribe(
                    audio_np, 
                    beam_size=1, # fast decoding
                    vad_filter=True, # filter blank audio
                    vad_parameters=dict(min_silence_duration_ms=500)
                )
                
                for segment in segments:
                    # Live Chunk Alignment: align segment text with the active speaker from diart timeline
                    seg_start = start_time - 2.0 + segment.start
                    seg_end = start_time - 2.0 + segment.end
                    
                    # Find dominant speaker in this segment's window from diart timeline
                    speaker = "Speaker 1"
                    speaker_overlap = {}
                    for d_start, d_end, d_spk in diarization_timeline:
                        overlap = max(0, min(seg_end, d_end) - max(seg_start, d_start))
                        if overlap > 0:
                            speaker_overlap[d_spk] = speaker_overlap.get(d_spk, 0) + overlap
                    
                    if speaker_overlap:
                        speaker = max(speaker_overlap, key=speaker_overlap.get)
                        
                    # Output aligned segment text to stdout
                    out_msg = {
                        "status": "transcription",
                        "text": segment.text.strip(),
                        "speaker": speaker,
                        "start": seg_start,
                        "end": seg_end
                    }
                    print(json.dumps(out_msg))
                    sys.stdout.flush()
                    
                # Clear transcription buffer periodically to keep memory footprint flat
                rolling_audio_buffer = rolling_audio_buffer[int(1.0 * SAMPLE_RATE):] # keep sliding overlap
                
            # Prune diarization_timeline to keep memory consumption flat
            if len(diarization_timeline) > 1000:
                diarization_timeline = diarization_timeline[-500:]

    except KeyboardInterrupt:
        pass
    except Exception as e:
        print(json.dumps({"status": "error", "message": f"Runtime error: {traceback.format_exc()}"}))
    finally:
        try:
            stream.stop_stream()
            stream.close()
        except:
            pass
        p.terminate()

if __name__ == "__main__":
    main()
